# R-6: Kafka DLT 조회·재처리 (replay)

> 2026-07-03 시스템 감사 §6-15 · ADR 0084 P0-4 (OrderDltMonitorListener) · KafkaErrorHandlerConfig(DefaultErrorHandler 1s×3 재시도 → `<topic>.DLT`) evidence 기반

## 0. 사용 시점

다음 evidence 중 하나라도 발견 시:
- Slack P0 알림 `:boom: Kafka DLT 수신 (P0 자본 path risk)` (OrderDltMonitorListener → NotificationFormatter)
- Grafana/Prometheus `magicjar_dlt_received_total{topic=...}` 증가 (감사 §6-15 신설 counter)
- `docker logs magicjar-consumer 2>&1 | grep "OrderDltMonitorListener DLT 수신"` 발견
- Kafka UI(:8090)에서 `*.DLT` 토픽에 메시지 잔존

**4 DLT 토픽과 자본 영향** (KafkaTopics.Dlt — 파일명 축약이 아닌 실제 토픽명 주의):

| # | DLT topic | 원본 listener | 자본 영향 |
|---|-----------|--------------|----------|
| 1 | `kafka.shared.cmd.order.intent.v1.DLT` | OrderRouterKafkaListener (자본) | ★★★ 주문 자체 미발사 |
| 2 | `kafka.consumer.evt.order.requested.v1.DLT` | STOMP fanout · Notification | ★★ 사용자 알림 누락 |
| 3 | `kafka.consumer.evt.order.rejected.v1.DLT` | STOMP fanout · Notification | ★★ 거절 알림 누락 |
| 4 | `kafka.consumer.evt.order.filled.v1.DLT` | STOMP fanout · Notification | ★★★ 체결 알림 누락 (자본 잠재) |

R-1 §2 분류에서 KAFKA/DLT 판정 시 본 runbook 진입.

---

## 1. 진단 (Diagnose) — DLT 내용 조회

kafka 컨테이너명 = `magicjar-kafka`, Kafka CLI 경로 = `/opt/kafka/bin` (apache/kafka:3.7.0 — docker-compose.yml 정합).

### 1-1. DLT 토픽별 메시지 유무·건수

```bash
# 4 DLT 토픽 존재 확인
docker exec magicjar-kafka /opt/kafka/bin/kafka-topics.sh \
  --bootstrap-server localhost:9092 --list | grep '\.DLT$'

# 토픽별 누적 offset (= 총 유입 건수 근사)
docker exec magicjar-kafka /opt/kafka/bin/kafka-run-class.sh kafka.tools.GetOffsetShell \
  --broker-list localhost:9092 --topic kafka.shared.cmd.order.intent.v1.DLT
```

### 1-2. DLT payload 전량 열람 (read-only — 자본 영향 0)

```bash
# order.intent DLT 전량 dump (key = userId 파티션 키 함께 출력, 5초 대기 후 종료)
docker exec magicjar-kafka /opt/kafka/bin/kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic kafka.shared.cmd.order.intent.v1.DLT \
  --from-beginning --timeout-ms 5000 \
  --property print.key=true --property print.headers=true
```

- `--property print.headers=true` — DeadLetterPublishingRecoverer가 첨부한 원본 topic·partition·offset·예외 스택 헤더(`kafka_dlt-original-topic`·`kafka_dlt-exception-message` 등) 확인. **실패 원인 분류의 1차 evidence.**
- 나머지 3 토픽도 `--topic`만 바꿔 동일 조회.

### 1-3. 실패 원인 분류

| 헤더 `kafka_dlt-exception-*` 패턴 | 분류 | replay 가능? |
|----------------------------------|------|-------------|
| JSON parse/deserialize 예외 | payload 스키마 결함 (배포 간 스키마 drift) | 코드 fix 전 replay 무의미 |
| DB/Redis connection 예외 | 일시 인프라 결함 | **replay 후보** (인프라 회복 확인 후) |
| `ORDER_IDEMPOTENCY_REDIS_DOWN` | 멱등 가드 fail-closed (자본 보호 LOCK) | Redis 회복 후 **replay 후보** |
| 비즈니스 검증 예외 (risk reject 등) | 정상 fail-closed | replay 금지 — 원인 fix가 본질 |

---

## 2. 격리·판단 (Isolate)

### 2-1. replay 전 의무 체크 (자본 경로 — 순서 LOCK)

1. **모드 확인** — `.env` `MAGICJAR_MODE` + `MAGICJAR_ALLOW_REAL`. REAL 이중 게이트 상태에서 `order.intent.v1` replay는 **실주문 재발사**다.
2. **이미 처리됐는지 확인** — DLT에 남았어도 KIS WS pre-insert(`WS_PREINSERT:{kisOrderNo}`) 등 별도 경로로 주문이 이미 반영됐을 수 있다:
   ```sql
   -- payload의 idempotencyKey / userId / code 로 기존 주문 존재 확인
   SELECT id, code, side, quantity, status, idempotency_key, requested_at
   FROM order_request
   WHERE idempotency_key = '<payload.idempotencyKey>';
   ```
3. **시세 신선도** — 장중 발사된 intent를 장 마감 후/익일 replay하면 진입가 전제가 무너진다. **발생 후 수 분 이상 지난 order.intent는 replay하지 않고 폐기 + 전략 재평가에 위임**이 default.
4. **KillSwitch 상태 확인** — R-3. replay 도중 사고 시 즉시 차단 lever 확보.

### 2-2. 멱등 방어선 (replay가 이중 주문을 만들지 않는 이유 — 그래도 §2-1 생략 금지)

- **OrderRouter Redis SETNX** — `OrderIdempotencyGuardImpl`이 `idem:order:{idempotencyKey}` SETNX. 동일 키 재유입은 skip, Redis down이면 fail-closed(주문 미발사).
- **auto-\* 멱등 키 체계** (`AutoOrderIdempotencyKeys`) — 자동 주문은 키 자체가 결정적이라 replay가 안전한 편:

  | prefix | 발사 주체 |
  |--------|----------|
  | `auto-tp` | AutoExitOrderCoordinator (익절) |
  | `auto-tpt` | 익절 tick watcher |
  | `auto-sl` | PositionStopLossTickWatcher (손절) |
  | `auto-vi` | PositionViAutoExitWatcher (VI 자동 청산) |
  | `auto-lq` | PlanExecutor / autopilot 청산 |

- ★ 단 **Redis 키 TTL 만료 후 replay는 SETNX를 다시 통과**한다 — 오래된 intent일수록 §2-1-3 폐기 원칙 우선.
- evt 계열(requested/rejected/filled DLT)은 read-model·알림 경로라 이중 유입 시 알림 중복까지가 최대 피해(자본 write 0).

---

## 3. 수동 replay 절차 (Resolve)

### 3-1. 단건 replay — 원본 토픽으로 재발행

DLT payload를 §1-2로 복사한 뒤 **원본 토픽**에 동일 key(userId)로 재발행:

```bash
# key:value 형식 (구분자 |) — key = 원본 파티션 키 (order.intent = userId)
docker exec -i magicjar-kafka /opt/kafka/bin/kafka-console-producer.sh \
  --bootstrap-server localhost:9092 \
  --topic kafka.shared.cmd.order.intent.v1 \
  --property parse.key=true --property key.separator='|' <<'EOF'
<userId>|<DLT에서 복사한 payload JSON 원문 그대로>
EOF
```

- payload는 **한 글자도 수정하지 않는다** (idempotencyKey 변조 = 멱등 방어선 붕괴 → 이중 주문).
- 여러 건이면 한 건 replay → §4 검증 → 다음 건 (일괄 재발행 금지 — 자본 경로).

### 3-2. replay 직후 소비 확인

```bash
docker logs --since 2m magicjar-consumer 2>&1 | grep -E "OrderRouterKafkaListener|idem:order|skip"
# 정상 A: 주문 처리 로그 진행 (신규 처리)
# 정상 B: idempotency skip 로그 (이미 처리된 건 — 안전하게 무시됨)
# 결함  : 다시 DLT 재유입 → §1-3 원인 분류로 회귀 (코드 fix 없이 재시도 금지)
```

### 3-3. 폐기 결정 시 (replay 불가 판정)

DLT 메시지는 소비해도 사라지지 않는다(consumer group offset일 뿐). 폐기는 "그대로 둔다"가 default — retention(1h/200MB, docker-compose.yml KAFKA_LOG_RETENTION_*)이 자연 정리한다. 폐기 사실과 사유를 post-mortem에 기록.

---

## 4. 검증 (Verify)

```bash
# 1) DLT 재유입 0 확인 (replay 후 5분)
docker logs --since 5m magicjar-consumer 2>&1 | grep "OrderDltMonitorListener DLT 수신"
# → 0 row 기대

# 2) metric 재확인 — magicjar_dlt_received_total 증가 정지
curl -s http://localhost:8082/actuator/prometheus | grep magicjar_dlt_received_total
```

```sql
-- 3) order.intent replay 건의 주문 원장 정합 (한 건당 order_request 정확히 1 row)
SELECT idempotency_key, COUNT(*) FROM order_request
WHERE requested_at >= NOW() - INTERVAL '30 minutes'
GROUP BY idempotency_key HAVING COUNT(*) > 1;
-- → 0 row 기대 (이중 주문 없음)
```

- frontend `/orders` 목록 + `SignalTracePanel`로 사인→주문→체결 사슬 확인 (ADR 0115 §2-B 불변식 #4).

---

## 5. post-mortem

R-1 §6 통합. 추가 evidence:
- 동일 예외 유형 DLT 재발 ≥ 2회 → 원본 listener 결함 fix dispatch (backend-core)
- `order.intent.v1.DLT` REAL 모드 유입 1회라도 → 즉시 사용자 보고 + KillSwitch 검토 이력 기록
- DLT 대응 자동화(재시도 backoff 조정·replay 도구화) 필요 판단 시 `_workspace/backlog.md` row 추가

### 5-1. ADR cross-link

- **ADR 0084 P0-4** — OrderDltMonitorListener 발행 권위 (DLT 4 토픽 + P0 Slack)
- **ADR 0067·0068** — order.intent 발행·소비 계약 (idempotencyKey 스키마)
- **ADR 0095** — 관측 SLO routing matrix (`magicjar_dlt_received_total` alert 후속 의제)
- **ADR 0096** — backup/DR (DLT는 백업 대상 아님 — retention 1h 휘발 주의)

---

## 6. 변경 이력

| 날짜 | 변경 |
|------|------|
| 2026-07-03 | 신규 발행 — 시스템 감사 §6-15 (DLQ depth 메트릭 0 + 재처리 runbook 0) 대응. DLT 조회·원인 분류·멱등 검증 포인트(auto-\*·Redis SETNX)·단건 replay 절차 + 자본 경로 주의사항. |

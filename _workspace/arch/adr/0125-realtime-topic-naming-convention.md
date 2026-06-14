# ADR 0125 — 실시간 토픽 명명규칙 채택 + wire clean-cut 마이그레이션

- 상태: **Accepted (2026-06-15, 최고 관리자 직접 결재 — Phase 2 실제 wire 교체 결정)**
- 작성자: architect (opus 4.8, 1M)
- 관계: 근거 문서 `docs/architecture/backup/realtime-topic-inventory.md`(제안표 = 새 이름 권위 — 본 ADR이 그 DRAFT를 Accepted로 승격), ADR 0007(Kafka 이벤트 백본), ADR 0084 P0-4(`KafkaErrorHandlerConfig` DLT auto-suffix `.DLT` — 본 ADR이 불변 보존), ADR 0088(persistence-shared Outbox — `order_event_outbox.topic` DB 영속이 cut 안전 게이트 근거), ADR 0123·0124(KIS WS 채널·tr_id 회계 — KIS WS alias 동작 0 변경 정합), ADR 0048(WS slot 회계 — `WsSlotBudgetLedger` channelKey 불변 보호), 헌법 §3(KIS-first raw)·§9-1(SLO)·§9-2(변경 관리·canary)·§9-5(운영 양식 3슬롯)·§11-2(silent fallback 금지)
- 진입 트랙: 운영 고도화 (실시간 토픽 명명규칙 정합 layer)
- 자본 결정권: **0** (메시지 전송 layer 명명 정합 — 주문 발사 로직 X. 단 자본 path 토픽 이름이 바뀌므로 cut 시점 in-flight=0 무손실 게이트 의무)

---

## 1. 컨텍스트

magicJar의 Kafka topic 41건, STOMP destination 17 패턴, KIS WebSocket `tr_id` 7건은 현재 `market.tick.v1`·`order.filled.v1`·`/topic/market.tick.{code}` 같은 평면적 이름을 쓴다. 이름만으로 transport·owner module·message kind(event/command)·domain이 드러나지 않아, 운영 UI와 로그에서 "이게 Kafka source event인지 STOMP fanout인지", "어느 모듈이 contract owner인지"가 헷갈린다(근거 문서 §Summary).

**Phase 1(레지스트리화 — 머지 완료, PR #966·#967·#968)**: raw 토픽/destination 리터럴을 중앙 레지스트리(`domain/messaging/KafkaTopics.kt` · `api/messaging/StompDestinations.kt` · `consumer/ws/KisWsChannels.kt` · `frontend/src/lib/stompDestinations.ts`)로 모았다. **wire 문자열은 불변** — 새 명명규칙은 `nextName:` 주석으로만 보존했다(REAL canary 중 무위험). 모든 raw 리터럴이 레지스트리 단일 지점으로 수렴했다.

**Phase 2(본 ADR — 실제 wire 교체)**: 최고 관리자 직접 결재로, 레지스트리의 wire 값을 새 명명규칙으로 **clean cut** 교체하기로 결정했다. 레지스트리가 단일 지점이 되었으므로 wire 변경이 원자적이고 롤백 가능하다. 본 ADR은 근거 문서의 제안표를 권위로 승격하고, clean-cut 마이그레이션을 LOCK한다.

본 ADR은 **결정 근거 문서**다 — 실제 코드 플립(레지스트리 wire 값 교체 + literal-assert 테스트 정정)은 backend·frontend 에이전트가 병렬 진행한다. 본 ADR은 ADR 문서·README 인덱스·inventory status 줄만 다룬다(코드 0 touch).

**KIS-first raw 검증 (헌법 §3)**: KIS WebSocket outbound `tr_id`(`H0UNCNT0`·`H0UNASP0` 등)는 KIS OpenAPI 계약이므로 **절대 불변**이다(raw 출처 `domestic_stock_functions_ws.py` tr_id 목록 — ADR 0124 §KIS-first 표 1:1 정합). 본 ADR의 KIS 항목은 **내부 표시 alias만** 도입하며 outbound tr_id·slot 회계는 0 변경한다.

---

## 2. 검토한 대안 (마이그레이션 방식)

| 대안 | 장점 | 단점 |
|------|------|------|
| **A. clean cut (채택)** | 단일 운영자 환경에 단순 — dual-write 코드·idempotency·이중 lag 모니터 불필요. 레지스트리 단일 지점이라 PR revert로 원자적 롤백. 죽은 옛 토픽 분기 누적 0 | cut 시점에 in-flight 메시지가 있으면 옛 토픽으로 새어 무손실 보장 깨짐 → drain 게이트로 닫아야 함. 재배포 필요(장 마감 야간) |
| B. dual-write (근거 문서 §Migration Plan 원안) | zero-downtime — producer가 옛+새 토픽 동시 발행, consumer 양쪽 listen, lag=0 후 옛 토픽 cut. 운영 무중단 | 한 release 동안 이중 발행·이중 listen·idempotency 코드 + 이중 DLT 모니터 부담. 단일 운영자 canary 환경엔 과설계. 죽은 dual-write 분기 제거 follow-up 필요 |

**선택 = A (clean cut)**. 사유: magicJar는 **단일 운영자 canary** 환경이고, 장 마감 후 야간에 **전체 스택 재배포가 가능**하다(in-flight=0 보장 가능). dual-write의 zero-downtime 이득은 다중 운영자·24/7 무중단 환경에서 가치가 크지만, 현 단계엔 불필요한 복잡도다. 레지스트리가 단일 지점이 되어 clean cut의 유일한 risk(in-flight 누수)는 drain 게이트(§3-5)로 닫힌다. **dual-write 절차는 근거 문서에 "향후 zero-downtime 운영 단계 진입 시 표준"으로 보존 기록**한다(폐기 아님 — 미래 채택 후보).

---

## 3. 결정 (2026-06-15 최고 관리자 직접 결재 — 5건 LOCK)

### 3-1. 명명규칙 채택 (근거 문서 제안표 = 권위)

근거 문서 `realtime-topic-inventory.md`의 제안표를 **Accepted 권위**로 승격한다. 전체 매핑(Kafka 41건·STOMP 17 패턴·KIS alias 7건)은 그 문서가 단일 SoT다.

```text
Kafka:  kafka.{owner}.{kind}.{domain}.{resource}.{event}.v{n}
STOMP:  /topic/stomp.api.{domain}.{stream}[.{key}]
KIS:    kisws.consumer.{domain}.{stream}.{variant}.{trId}   (내부 표시 alias만)
```

| segment | 의미 | 예 |
|---|---|---|
| `owner` | contract owner 모듈 | `api`·`consumer`·`batch`·`shared`(다중 모듈 발행 시) |
| `kind` | 메시지 종류 | `evt`(이미 발생한 사실)·`cmd`(다른 모듈에 일을 시킴)·`dlt` |
| `domain` | 업무 영역 | `market`·`order`·`risk`·`strategy`·`reconcile`·`account`·`system`·`ai` |
| `resource.event` | 무엇이 일어났나/요청됐나 | `tick`·`filled`·`manual-trigger` |
| `v{n}` | 스키마 버전 | `v1` |

대표 매핑(전체는 근거 문서):
- `market.tick.v1` → `kafka.consumer.evt.market.tick.v1`
- `order.intent.v1` → `kafka.shared.cmd.order.intent.v1`
- `order.filled.v1` → `kafka.consumer.evt.order.filled.v1`
- `signal.generated.v1` → `kafka.consumer.evt.strategy.signal-generated.v1`
- `reconcile.drift.v1` → `kafka.consumer.evt.reconcile.drift.v1`
- `/topic/market.tick.{code}` → `/topic/stomp.api.market.tick.{code}`
- `/topic/order.user.{userId}` → `/topic/stomp.api.order.user.{userId}`
- `H0UNCNT0` → 내부 alias `kisws.consumer.market.tick.unified.H0UNCNT0` (outbound tr_id 불변)

STOMP는 근거 문서가 제안한 `/topic/stomp.{owner}.{domain}...` 중 owner를 **`api`로 고정**한다(STOMP fanout은 전부 API-side). `stomp.api` prefix로 "이건 Kafka source event가 아닌 API WebSocket fanout"임이 이름에서 즉시 드러난다.

### 3-2. 마이그레이션 = clean cut (dual-write 미채택)

레지스트리의 wire 값을 새 이름으로 **한 번에 교체**한다(§2 대안 A). 옛 토픽은 cut 후 자동 삭제하지 않고 **orphan으로 retention 만료 방치**한다(Kafka retention이 자연 소멸시킴).

- **cut 안전 게이트 (§3-5 배포 전 필수)**: cut 시점 in-flight=0 보장.
- **롤백 경로**: Phase 2 PR revert + 재배포. 레지스트리가 단일 지점이라 wire 값이 옛 이름으로 원자 복구된다. 옛 토픽이 orphan으로 아직 존재하면(retention 내) consumer가 즉시 다시 붙는다.
- **dual-write 절차 보존**: 근거 문서 §Migration Plan의 dual-write·이중 listen·STOMP 병행 fanout 절차는 "향후 zero-downtime 운영 단계 진입 시 표준"으로 보존한다. 현 clean cut이 그 절차를 폐기하는 것이 아니다.

### 3-3. DLT 명명 = auto-suffix 유지 (근거 문서 infix 제안 미채택)

`KafkaErrorHandlerConfig`의 DLT destination resolver는 `val targetTopic = "${record.topic()}.DLT"` 규칙(원본 topic suffix `.DLT`)을 **건드리지 않는다**. 따라서 DLT 토픽은 **새 main 토픽 + `.DLT`** 가 자동으로 따른다:

| 옛 main → DLT | 새 main → DLT (auto-suffix) |
|---|---|
| `order.intent.v1` → `order.intent.v1.DLT` | `kafka.shared.cmd.order.intent.v1` → `kafka.shared.cmd.order.intent.v1.DLT` |
| `order.requested.v1.DLT` | `kafka.consumer.evt.order.requested.v1.DLT` |
| `order.rejected.v1.DLT` | `kafka.consumer.evt.order.rejected.v1.DLT` |
| `order.filled.v1.DLT` | `kafka.consumer.evt.order.filled.v1.DLT` |

근거 문서가 제안한 `kafka.{owner}.dlt.{domain}...v1`(dlt infix) 형태는 **미채택**한다. 이 deviation 사유: error handler의 `${record.topic()}.DLT` resolver를 infix 형태로 바꾸려면 topic→DLT 매핑을 재작성해야 하고(자본 path `order.*.v1.DLT` 4 토픽 모니터 — ADR 0084 P0-4 `OrderDltMonitorListener`), 재작성 risk가 명명 일관성 이득보다 크다. auto-suffix는 main 토픽이 새 이름이 되는 순간 DLT도 자동 추종하므로 추가 작업 0이다. **근거 문서 §Recommended Naming Rule의 `kind=dlt` 항목과 §DLT 표는 본 deviation에 맞춰 "auto-suffix 유지"로 정합 갱신 권장**(backend 플립 PR에서 동반 또는 후속 architect 정정).

### 3-4. KIS WebSocket = outbound tr_id 절대 불변 + alias는 표시 전용

KIS WS outbound `tr_id`(`H0UNCNT0`·`H0UNASP0`·`H0UNMKO0`·`H0STOUP0`·`H0STCNI0`·`H0STCNI9`·`H0STCNT0`)는 **KIS OpenAPI 계약**이므로 절대 변경 금지(헌법 §3). 내부 alias(`kisws.consumer.{domain}.{stream}.{variant}.{trId}`)는 **로그·메트릭·운영 표시 라벨에만** 적용 가능하며, KIS WS 동작은 0 변경한다.

- **`WsSlotBudgetLedger`의 slot channelKey(`trId|code`)는 불변** — slot 회계(ADR 0048·0123·0124)의 안정성을 보호한다. alias는 회계 키가 아니라 표시 라벨일 뿐이다. channelKey를 alias로 바꾸면 slot 점유/eviction 회계가 깨질 risk가 있으므로 LOCK.
- 즉 KIS는 "표시 alias만, 동작 0 변경". alias 도입은 선택 사항이며, 본 ADR이 강제하지 않는다(운영 표시 개선 follow-up).

### 3-5. cut 안전 게이트 (배포 전 필수 검증)

자본 path 토픽(`order.*`·`reconcile.*`·`risk.*`) 이름이 바뀌므로, cut 시점에 옛 토픽으로 새는 메시지가 있으면 무손실 보장이 깨진다. 다음 2건을 cut 직전 필수 확인한다:

1. **`order_event_outbox` PENDING·FAILED = 0** (이미 검증됨: 0건). outbox가 topic 문자열을 DB 영속(`order_event_outbox.topic: String` — `OrderEventOutboxJpaEntity` line 49)하므로, 미발행 row가 있으면 그 row가 **옛 토픽 문자열을 들고** 새 배포에서 발행 시도 → 옛 토픽으로 샌다. 반드시 0 확인 후 cut.
2. **Kafka 자본 토픽 consumer lag = 0** (`order.*`·`reconcile.*`·`risk.*`). in-flight 메시지가 옛 토픽 partition에 남아 있으면 새 배포 consumer가 새 토픽만 listen하므로 미처리. lag=0 = in-flight 0 = 무손실 cut 조건.

게이트 미충족 시 cut 금지 — drain 완료까지 대기 후 재시도.

---

## 4. 결과

### 4-1. 운영 양식 3 슬롯 (헌법 §9-5)

**SLO 영향**: 정상 운영 중 SLO(§9-1)에 **영향 없음** — 명명 정합만 바뀌고 토픽 수·partition·throughput·지연은 불변. cut 순간만 재배포로 짧은 중단(야간 장 마감, 매매 비활성 — 자동매매 시그널 emit~주문 발사 지연 SLO 무영향 시간대). cut 후 정상 운영 SLO는 cut 전과 동일.

**rollback plan**:
- Phase 2 PR revert + 재배포(레지스트리 단일 지점 → wire 값 옛 이름 원자 복구).
- 옛 토픽이 orphan으로 retention 내 잔존하면 consumer 즉시 재attach. retention 만료 후라면 옛 토픽 `NewTopic` 자동 재생성(빈 토픽).
- `order_event_outbox` 미발행 row가 있는 상태로 롤백해도 outbox가 들고 있는 topic 문자열이 revert된 레지스트리 값과 일치하면 정상 발행(게이트 0 확인이 양방향 안전).
- **장중 배포 금지** — 장마감(15:30) 후 + 09:00 정규장 직전 재배포(canary §9-2). drain 게이트(§3-5) 통과가 cut 선결.

**관측 매트릭스**: cut 후 즉시 `docker logs` 4모듈(api·batch·consumer·frontend) 에러 0 확인(헌법 — release cut 직전 docker 검증 정합). Prometheus: Kafka 새 토픽 partition별 consumer lag 정상 drain 관측, DLT 토픽(`*.DLT`) 0건 유지. 옛 토픽 orphan은 `kafka-topics --list`에서 retention 만료까지 관측(자동 소멸 확인). STOMP: frontend 새 destination subscribe 후 실시간 tick·order·candle 화면 갱신 연속성 확인(시각 회귀 — frontend 핵심 화면 영향 시 qa Visual).

### 4-2. 자원 영향

신규 Flyway 0 / 신규 Kafka 토픽(이름만 변경, 신규 개념 0) / 신규 STOMP destination(이름만 변경) / 신규 REST 0 / 신규 ENV 0. KIS WS slot 회계 채널키 불변(§3-4). 본 ADR 자체는 **코드 0 touch**(md only — ADR·README·inventory status 줄). 실제 wire 플립 = backend·frontend 에이전트 병렬 PR(레지스트리 wire 값 교체 + literal-assert 테스트 정정).

### 4-3. 검증 (DoD)

1. 토픽 assert 테스트(레지스트리 참조형)는 레지스트리 값 변경을 자동 추종 → GREEN(코드 변경 0). literal-assert 테스트(`KafkaErrorHandlerConfigTest` 등 옛 토픽 문자열 하드코딩)는 backend 에이전트가 새 값으로 정정.
2. cut 안전 게이트(§3-5) 2건 0 확인 후 야간 재배포.
3. 재배포 후 `docker logs` 4모듈 에러 0.
4. 정규장(09:00) 자본 path(order/signal/fill/reconcile) 새 토픽으로 정상 흐름 + DLT 0건.
5. frontend 새 STOMP destination 화면 갱신 연속성(tick·candle·order).
6. KIS WS outbound tr_id·slot 회계 불변(`WsSlotBudgetLedger` channelKey 무변경).

### 4-4. 근거 문서 status 갱신

`docs/architecture/backup/realtime-topic-inventory.md` 상단 `> Status: DRAFT` 를 `> Status: Accepted (ADR 0125)` 로 갱신한다(status 줄만). 위치(`/backup`)는 inventory가 운영 권위 문서임을 감안하면 부적절하므로 `docs/architecture/`로 이동을 권장하나, **실제 경로 이동은 backend 플립과의 충돌 회피를 위해 본 ADR에서 하지 않는다**(후속 정리 PR — backend wire 플립 머지 후 architect 단독). 본 ADR은 status 줄만 수정한다.

---

## 5. 영향/리스크 — 자본 흐름 + canary

- **자본 흐름**: order/signal/fill/reconcile/risk/DLT 토픽 전부 새 이름. clean cut이므로 cut 시점 in-flight=0(§3-5 게이트)이어야 무손실. outbox가 DB에 topic 문자열을 영속하므로 PENDING/FAILED=0이 양방향(cut·롤백) 안전 조건.
- **canary 영향**: 익일 09:00 실전 장 직전 야간 재배포. 수용 조건 = drain 게이트(§3-5) 통과 + 자본 토픽 lag=0 + 재배포 후 `docker logs` 4모듈 에러 0. 미충족 시 cut 보류(REAL canary 무위험 원칙).
- **검증 자동화**: 레지스트리 참조 테스트는 GREEN 자동 추종. literal-assert(`KafkaErrorHandlerConfigTest`)는 backend가 정정. frontend STOMP destination은 `stompDestinations.ts` 단일 지점 → 화면 회귀 시 qa Visual.

---

## 6. 대안 (rejected) 요약

| 대안 | rejected 사유 |
|---|---|
| **dual-write 마이그레이션** | 단일 운영자 canary + 야간 재배포 가능 환경엔 과설계. 이중 발행·idempotency·이중 DLT 모니터 부담. zero-downtime 이득은 현 단계 불필요 → "향후 zero-downtime 운영 단계 표준"으로 보존(폐기 아님) |
| **DLT infix 형태**(`kafka.{owner}.dlt....v1`) | `KafkaErrorHandlerConfig`의 `${record.topic()}.DLT` resolver 재작성 risk(자본 path `order.*.DLT` 4 모니터). auto-suffix는 main 토픽 새 이름을 0 작업으로 추종 |
| **KIS tr_id 변경 또는 slot channelKey alias화** | tr_id는 KIS OpenAPI 계약(불변·헌법 §3). channelKey alias화는 slot 회계(ADR 0048·0123·0124) 안정성 깨짐 risk. alias는 표시 라벨 전용 |
| **inventory `/backup` → `docs/architecture/` 즉시 이동** | backend wire 플립과 경로 변경 동시 발생 시 충돌·diff 노이즈. status 줄만 갱신 + 이동은 후속 PR |

---

## 7. 참고

- 근거 문서(제안표 권위): `docs/architecture/backup/realtime-topic-inventory.md` (본 ADR이 DRAFT→Accepted 승격)
- ADR 0007 — Kafka 이벤트 백본
- ADR 0084 P0-4 — `KafkaErrorHandlerConfig` DLT auto-suffix `.DLT`(본 ADR이 불변 보존) + `OrderDltMonitorListener` 자본 path 4 DLT 모니터
- ADR 0088 — persistence-shared Outbox (`order_event_outbox.topic` DB 영속 = cut 게이트 근거)
- ADR 0048·0123·0124 — KIS WS slot 회계·채널·tr_id (alias 동작 0 변경 정합·channelKey 불변)
- 헌법 §3(KIS-first)·§9-1(SLO)·§9-2(canary)·§9-5(운영 양식)·§11-2(silent fallback 금지)
- Phase 1 PR: #966(api 레지스트리화)·#967(frontend `stompDestinations.ts`)·#968(messaging `KafkaTopics.kt` + KIS WS tr_id 문서화)
- 레지스트리 단일 지점: `domain/messaging/KafkaTopics.kt` · `api/messaging/StompDestinations.kt` · `consumer/ws/KisWsChannels.kt` · `frontend/src/lib/stompDestinations.ts`

## 8. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-06-15 | ADR 0125 본문 발행 (Accepted) — architect opus 4.8. 실시간 토픽 명명규칙 채택 + Phase 2 wire clean-cut 마이그레이션. 5건 LOCK(최고 관리자 직접 결재): ①명명규칙 채택(Kafka `kafka.{owner}.{kind}.{domain}.{resource}.{event}.v{n}` / STOMP `/topic/stomp.api.{domain}.{stream}[.{key}]` / KIS alias `kisws.consumer.{domain}.{stream}.{variant}.{trId}` — 전체 매핑=근거 문서 제안표 승격) ②clean cut(dual-write 미채택·옛 토픽 orphan retention 방치·PR revert 원자 롤백·dual-write는 향후 zero-downtime 표준 보존) ③DLT auto-suffix 유지(`${record.topic()}.DLT` 불변·근거 문서 dlt infix 미채택·error handler 재작성 risk 회피) ④KIS tr_id 절대 불변+alias 표시 전용·`WsSlotBudgetLedger` channelKey 불변 ⑤cut 안전 게이트(`order_event_outbox` PENDING/FAILED=0 검증됨 + 자본 토픽 lag=0). 근거 문서 DRAFT→Accepted 승격 + status 줄 갱신(경로 이동은 후속). 구현=backend·frontend 병렬 wire 플립. md only(코드 0 touch). |

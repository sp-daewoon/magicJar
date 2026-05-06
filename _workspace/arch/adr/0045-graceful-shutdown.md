# ADR 0045 — Graceful Shutdown & In-flight 처리 (SIGTERM · Kafka commit fence · Idempotency)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · PR-3b 운영·UX fitness 갭 2번)
- 관계: ADR 0008 보강(Docker compose 표준) · ADR 0005 정합(api/batch/consumer 3 JVM) · ADR 0011 정합(주문 실행기) · ADR 0007 정합(Kafka 백본) · ADR 0042 정합(Position Bootstrap·Recovery) · ADR 0044 정합(통지)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0008 docker compose 표준은 컨테이너 4개(api·batch·consumer·frontend) 통합 기동만 명시하고 **shutdown 동작은 침묵**. 자동매매 시스템에서 graceful shutdown 부재 시 발생 사고:

- `docker compose down` 시 SIGTERM → 강제 SIGKILL 10초 후 → **진행 중 KIS 주문 응답 무시** → DB는 `PENDING`, KIS 서버는 체결 → drift (ADR 0041은 사후 검출만)
- consumer Kafka poll 중 강제 종료 → offset commit 못 함 → 다음 부팅 시 **동일 message 재처리** → 같은 시그널로 같은 종목 재주문 (idempotency 없으면 중복 주문)
- batch job 진행 중 강제 종료 → Spring Batch 메타 테이블 `STARTED` 잔존 → 다음 부팅 시 `JobExecutionAlreadyRunningException`
- KIS WebSocket 연결 강제 끊김 → KIS 서버 측 한도(동시 41건) 잠시 점유 → 즉시 재기동 시 일부 종목 구독 실패

ADR 0042 (Position Bootstrap·Recovery)가 "재기동 시 잔고 복구"는 보장하나 **shutdown phase 자체 정의 부재**. 사용자 명시 = "in-flight 주문은 어떻게? Kafka commit은? idempotency 키는?"

**필요한 결정**:
- 3 JVM 컨테이너 각각의 shutdown phase 명세
- in-flight 주문 처리 정책 (cancel? 보존? KIS 응답 대기?)
- Kafka commit fence (manual commit 의무화)
- order.intent.v1 idempotency 키 표준
- docker compose 정합 (`stop_grace_period`)

## 2. 결정

### 2-1. 3 JVM 컨테이너 shutdown phase 명세

#### A. api 모듈 (Spring Boot Web + STOMP)

| Phase | 동작 | timeout |
|---|---|---|
| **1. SIGTERM 수신** | Spring Boot graceful shutdown 활성화 | 즉시 |
| **2. 신규 HTTP 거부** | 진행 중 요청은 처리, 신규는 503 반환 | - |
| **3. 진행 중 HTTP draining** | `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s` | 30s |
| **4. STOMP 연결 종료** | `system.shutdown.v1` Kafka publish → 모든 STOMP 세션 close | 5s |
| **5. Kafka producer flush** | producer.close() — pending 메시지 전송 보장 | 10s |
| **6. JVM 종료** | normal exit | - |

**application.yml 정합**:
```yaml
server:
  shutdown: graceful
spring:
  lifecycle:
    timeout-per-shutdown-phase: 30s
```

#### B. consumer 모듈 (Kafka consumer + KIS WS)

| Phase | 동작 | timeout |
|---|---|---|
| **1. SIGTERM 수신** | `system.shutdown.v1` Kafka publish (마지막 publish — flush 보장) | 즉시 |
| **2. Kafka consumer poll 정지** | `KafkaListenerEndpointRegistry.stop()` → poll() 중단 + 마지막 batch commit | 10s |
| **3. 진행 중 message 처리 완료 대기** | `@KafkaListener` 메서드 진행 중 종료 대기 | max 30s |
| **4. KIS WS 정리** | unsubscribe 모든 종목 → connection close | 5s |
| **5. 미체결 order intent 보존** | DB `orders.status=PENDING` **그대로 유지** (재기동 시 재처리) | - |
| **6. Kafka producer flush** | Outbound producer flush + close | 10s |
| **7. JVM 종료** | normal exit | - |

**미체결 주문 정책 (사용자 결정 필요시 명시)**:
- **PENDING 그대로 보존** (cancel 안 함) — KIS 서버에서 이미 체결됐을 가능성 → 재기동 시 ADR 0042 Recovery로 정합
- **단**, KIS REST 호출 진행 중인 경우 → 응답 대기 max 5초 후 응답 무시 → `orders.status=PENDING_UNKNOWN` 마킹 → 재기동 시 ADR 0042 + ADR 0041 reconcile로 진위 판정

#### C. batch 모듈 (Spring Batch + @Scheduled)

| Phase | 동작 | timeout |
|---|---|---|
| **1. SIGTERM 수신** | `system.shutdown.v1` Kafka publish | 즉시 |
| **2. @Scheduled 신규 invocation 차단** | `TaskScheduler.shutdown(false)` — 진행 중만 완료 | - |
| **3. 진행 중 Spring Batch step 처리** | `JobOperator.stop(jobExecutionId)` graceful API → step boundary에서 stop | max 60s |
| **4. mid-step interrupt safe** | Spring Batch 자동 — `BATCH_JOB_EXECUTION.status=STOPPED` checkpoint 저장 → 다음 부팅 `JobOperator.restart()` 가능 | - |
| **5. JVM 종료** | normal exit | - |

**예외**: `BalanceReconcileJob` (ADR 0041) 진행 중 → 다음 cron으로 자연 catch-up. 강제 중단 OK.

### 2-2. Kafka commit fence — manual commit 의무화

**현재 (개선 필요)**: Spring Kafka default = `enable.auto.commit=true` → 5초 주기 자동 commit → poll 중 종료 시 **이미 처리한 메시지가 미커밋** → 재처리.

**결정 (의무화)**: 모든 consumer는 manual commit.

```yaml
spring:
  kafka:
    consumer:
      enable-auto-commit: false
      auto-offset-reset: earliest
    listener:
      ack-mode: MANUAL_IMMEDIATE
```

**처리 흐름**:
```kotlin
@KafkaListener(topics = ["order.intent.v1"], containerFactory = "manualAckFactory")
fun handle(payload: OrderIntent, ack: Acknowledgment) {
    // 1. idempotency 체크 (2-3 정합)
    // 2. 처리 로직 (KIS API 호출 등)
    // 3. 성공 시에만 ack
    ack.acknowledge()
}
```

**예외 시 정책**:
- 비즈니스 예외 (잔고 부족 등) → audit 후 ack (재처리 무의미)
- 일시 장애 (KIS 5xx 등) → ack 안 함 → Kafka 재인도 → Resilience4j retry
- 영구 장애 → DLT(Dead Letter Topic) → ack

### 2-3. Idempotency 키 표준

#### A. order.intent.v1 메시지

| 필드 | 형식 | 예시 |
|---|---|---|
| `Idempotency-Key` (Kafka header) | `{user_id}:{strategy_id}:{ymd}:{tick_seq}` | `74683223:RSI_OS:20260507:0341` |

**조합 의미**:
- `user_id` (마스킹 X — 내부 처리만) — multi-tenant 분리
- `strategy_id` — 같은 종목 다른 전략 시 분리
- `ymd` (KST yyyyMMdd) — 일자 단위 reset
- `tick_seq` — 같은 분 내 다중 시그널 분리 (HHmm 또는 sequence number)

#### B. Redis SETNX 24h TTL — 중복 처리 차단

```kotlin
// consumer 처리 직전
val key = "processed:order:${header}"
val acquired = redisTemplate.opsForValue().setIfAbsent(key, "1", Duration.ofHours(24))
if (!acquired) {
    log.info("Duplicate order intent skipped: {}", key)
    ack.acknowledge()  // dedup → ack
    return
}
// 실제 처리 ...
```

**24h TTL** — KIS 거래일 단위 reset. 다음날 같은 키는 재사용 가능.

#### C. KIS API 호출 자체 idempotency

KIS REST는 자체 idempotency 보장 부재 (KIS API 한계). 따라서 **재시도 시 KIS 잔고 조회로 진위 확인 후** 재발사 결정. ADR 0041 reconcile + ADR 0042 recovery 정합.

#### D. 다른 topic 정책

| topic | idempotency 키 | 정책 |
|---|---|---|
| `order.intent.v1` | `{user}:{strategy}:{ymd}:{tickSeq}` | 위 §2-3 표준 |
| `signal.generated.v1` | `{user}:{strategy}:{symbol}:{ymd}:{minute}` | 같은 분 같은 종목 같은 전략 dedup |
| `position.drift.v1` | `{user}:{symbol}:{detectedAt}` | drift 검출 시점 unique |
| `risk.killswitch.v1` | `{user}:{toggleAt}` | 토글 시점 unique |
| `journal.recorded.v1` | `{user}:{orderId}` | 주문당 1회 |

각 listener의 처리 직전 Redis SETNX 24h TTL.

### 2-4. Docker compose 정합

```yaml
# docker/docker-compose.yml (모든 앱 컨테이너)
services:
  api:
    stop_grace_period: 60s   # SIGTERM 후 SIGKILL까지 60초 (Spring 30s + 여유)
    stop_signal: SIGTERM
  consumer:
    stop_grace_period: 90s   # KIS WS·Kafka·in-flight 처리 여유 (max 30+10+5+10)
    stop_signal: SIGTERM
  batch:
    stop_grace_period: 90s   # Spring Batch step boundary 60s + 여유
    stop_signal: SIGTERM
  frontend:
    stop_grace_period: 10s   # nginx — 즉시 종료 OK
```

**`docker compose down --timeout 90`** 권장 (root timeout — compose CLI level).

### 2-5. SIGTERM hook 구현 표준

**ApplicationContext shutdown hook** — Spring Boot 자동. `@PreDestroy` + `DisposableBean` + `SmartLifecycle` 활용.

```kotlin
@Component
class GracefulShutdownHook(
    private val kafkaTemplate: KafkaTemplate<String, String>,
) : SmartLifecycle {
    override fun start() {}
    override fun stop() {
        // §2-1 phase 1: system.shutdown.v1 publish
        kafkaTemplate.send("system.shutdown.v1", payload).get(5, TimeUnit.SECONDS)
    }
    override fun isRunning() = true
    override fun getPhase() = Int.MIN_VALUE  // 가장 먼저 stop
}
```

각 컨테이너 (api·batch·consumer)에 동일 패턴.

### 2-6. 데이터 영향

| 테이블 | 변경 | Flyway |
|---|---|---|
| `orders` (현행) | `status` enum에 `PENDING_UNKNOWN` 추가 (consumer KIS REST 응답 미수신) | V18 보강 |
| (Spring Batch 자동 — `BATCH_JOB_EXECUTION.status=STOPPED`) | DDL 변경 없음 | - |

**Kafka topic 신규** (ADR 0044 정합 — 동일 topic 재사용):

| topic | 본 ADR 책임 |
|---|---|
| `system.startup.v1` | ApplicationReadyEvent → publish |
| `system.shutdown.v1` | SmartLifecycle.stop() → publish |

(NotificationDispatcher subscribe는 ADR 0044 책임)

### 2-7. 환경변수

| 변수 | default | 용도 |
|---|---|---|
| `MAGICJAR_SHUTDOWN_TIMEOUT_SEC` | `30` | api graceful HTTP draining |
| `MAGICJAR_CONSUMER_DRAIN_TIMEOUT_SEC` | `30` | consumer message 처리 대기 |
| `MAGICJAR_BATCH_STOP_TIMEOUT_SEC` | `60` | Spring Batch step boundary 대기 |

application.yml에서 위 값을 placeholder로 인용.

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-W1 (W1)** — application.yml `server.shutdown=graceful` + `spring.lifecycle.timeout-per-shutdown-phase=30s` 설정 의무 (api 모듈).
- **T-MS1 (W1)** — Kafka consumer manual ack 설정 의무 (consumer 모듈).
- **T-O (W3)** — order.intent.v1 idempotency 키 표준 통합. `PENDING_UNKNOWN` 상태 처리.
- **T-RECON (W3)** — `PENDING_UNKNOWN` orders는 reconcile 시 KIS 잔고와 비교 후 진위 판정.
- **신규 트랙 X** — graceful shutdown은 인프라성 — 각 트랙에 횡단 합류.

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `api` | `server.shutdown=graceful` + GracefulShutdownHook + Kafka producer flush |
| `consumer` | manual ack 설정 + 미체결 order PENDING 보존 + KIS WS unsubscribe + Idempotency Redis SETNX |
| `batch` | `JobOperator.stop()` graceful 호출 + @Scheduled shutdown |
| `frontend` | (영향 없음 — nginx static) |
| `domain` | `OrderStatus.PENDING_UNKNOWN` enum 추가 |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| `system.shutdown.v1` 빈도 | 1일 ≥ 3회 (의도적 재시작 외) → ALERT (OOM·crash 의심) |
| `orders.status=PENDING_UNKNOWN` 누적 | 30분 동안 ≥ 5건 → reconcile 강제 trigger 권고 |
| Spring Batch `STOPPED` job | 부팅 시 자동 restart 또는 사용자 결정 |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **default Spring Boot (auto-commit + 강제 SIGKILL 10s)** | 폐기 | 메시지 재처리 + in-flight 주문 사고 위험 |
| (b) **in-flight 주문 강제 cancel** | 폐기 | 이미 KIS 서버 체결됐을 수 있음 → 사후 reconcile이 안전 |
| (c) **graceful 30s + manual commit + idempotency Redis** (채택) | ✅ | Kafka commit 보장 + 재처리 dedup + 사후 reconcile 정합 |
| (d) DLT immediate failover | 후속 | 1차는 manual commit + retry로 충분. DLT는 영구 장애 시만 |

## 4. 후속 ADR 후보

- **0080+ (가칭)** — Dead Letter Topic 표준 (영구 장애 메시지 분류·alert·재처리 UI)
- **0081+ (가칭)** — Spring Batch failover 자동 restart (현재는 사용자 결정)
- **0082+ (가칭)** — 멀티 인스턴스 deploy 시 graceful rolling update (현재는 단일 인스턴스 가정)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0045 신규 발행 — PR-3b 운영·UX fitness 갭 2번. 3 JVM phase 명세 + Kafka manual commit + idempotency 키 표준 + docker stop_grace_period. (architect) |

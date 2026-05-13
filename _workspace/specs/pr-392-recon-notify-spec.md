# PR-392 spec — T-RECON + T-NOTIFY 묶음 spec/plan (ADR 0070)

> **권위 ADR**: [`_workspace/arch/adr/0070-t-recon-notify-bundled-spec.md`](../arch/adr/0070-t-recon-notify-bundled-spec.md) + baseline ADR 0041·0044
> **사이클**: v3.6.2 patch (W3 마무리 — T-O 마감 후 자본 보호 prereq)
> **작성**: 2026-05-13 architect (사용자 위임 자율 최종권한 — "이제 이건 다 두고 W3 진행해서 마무리까지하자 W4 진입까지 자율")

---

## §1. 두 트랙 묶음 사유 (자본 보호 prereq 단일 의제)

### 1-1. 사이클 위치

- v3.6.0 (2026-05-13 cut): **T-O 종착 노드 마감** — ADR 0068 A-1~H-1 채택, 5 PR(#371·#372·#373·#374·#375), 3중 게이트 자본 보호 LOCK
- **v3.6.2 (본 사이클)**: T-RECON + T-NOTIFY 묶음 — 자본 보호 prereq 안전망
- v3.6.3 (다음 사이클): T-SC (Screener) — 잠재 종목 발굴
- v3.6.4 (다음 사이클): T-J + T-PERF — 매매일지·일별 통계
- v3.7.0 minor cut: W3 완전 마감 직후 W4 (AI 옵션) 진입

### 1-2. 자본 보호 흐름 LOCK

```
[KIS 잔고 cron 5분 (시장 OPEN)]
   ↓
[BalanceReconcileJob (consumer @Scheduled)]
   ↓
[KisBalanceAdapter.fetchAllBalances() — 페이징 흡수]
   ↓
[BalanceReconciler.compare(kis, internal)]
   ↓
[수량 diff ≥ 1주 또는 종목 누락 검출?]
   ↓ YES                                    ↓ NO
[DB position upsert (KIS 신뢰원)]          [reconcile.run.v1 publish — audit only]
[Redis position:current:{userId} SET]
[Kafka reconcile.drift.v1 publish]
   ↓ (KillSwitchAutoToggler listen)
[kill_switch_state.enabled = true]
[Kafka killswitch.engaged.v1 publish]
   ↓ (NotificationDispatcher listen)
[Slack POST P0 즉시 (rate limit 0)]
[outbox row INSERT status=SENT]
   ↓ (StompReconcileNotifier listen)
[STOMP /topic/recon.user.{userId} fanout]
```

자본 사고 흐름 단일 — **drift → KillSwitch → Slack → STOMP**. 분리 PR 사이클로 디스패치 시 토픽 정합 risk + 사용자 의제 한 호흡 분리 부적합.

### 1-3. fitness 검증 (사용자 메모리 `feedback_v3_skepticism_no_regression` 정합)

| 검증 | 결과 |
|---|---|
| 자동매매 본체에 필요? | ★★★ — T-RECON 자본 사고 직결 (drift 시 이중 매매) + T-NOTIFY 사용자 부재 중 인지 의무 |
| v1/v2 잔재? | 0 — v3 multi-tenant + Kafka KRaft 기반 신규 의제 |
| AI 호출? | 0 — 룰/cron 기반, sub-agent 산출물 grep 검증 의무 |
| KIS-first raw 인용? | 4건 (§8) |
| mock 합성? | 0 — KIS REST 실제 응답 또는 "잔고 0건" 명시 표시 |

---

## §2. Phase 분리 LOCK

### 2-1. 직렬 디스패치 매트릭스

**원칙**: T-NOTIFY는 T-RECON Phase 1·2의 `reconcile.drift.v1`·`killswitch.engaged.v1` 토픽 listen 의무 — 토픽 prerequisite 정합. 따라서 **T-RECON Phase 1 → Phase 2 → T-NOTIFY Phase 1** 직렬.

| 순서 | Phase | sub-agent | 영역 | 의존 |
|---|---|---|---|---|
| 1 | **T-RECON Phase 1** | **strategy-engine** | consumer + domain | ADR 0041 §2 baseline + ADR 0070 §2 |
| 2 | **T-RECON Phase 2** | **backend-core** | api + Flyway | Phase 1 머지 후 |
| 3 | **T-NOTIFY Phase 1** | **backend-core** | consumer + api + Flyway | Phase 2 머지 후 |

**frontend 0 LOCK** (ADR 0070 §2-8): SettingsModal·KillSwitch baseline reuse + Risk 페이지 reconcile timeline UI는 후속 backlog §N.

### 2-2. T-RECON Phase 1 산출물 매트릭스 (strategy-engine)

| 영역 | 파일 | 책임 |
|---|---|---|
| **domain** | `domain/.../reconcile/ReconcileRun.kt` | 도메인 모델 (id, userId, startedAt, finishedAt, driftCount, status, lastError) |
| **domain** | `domain/.../reconcile/ReconcileResult.kt` | sealed (Reconciled·Drifted(holdings)·Error) |
| **domain** | `domain/.../reconcile/HoldingDiff.kt` | symbol·kisQty·internalQty·qtyDiff·avgPriceDiff |
| **domain** | `domain/.../reconcile/ReconcileStatus.kt` | enum (RUNNING·RECONCILED·DRIFT·DRIFT_PRICE·ERROR) |
| **domain port** | `domain/.../reconcile/port/KisBalancePort.kt` | `fun fetchAllBalances(userId: Long): List<KisHolding>` (헌법 §6 abstract LOCK) |
| **domain port** | `domain/.../reconcile/port/ReconcileRunRepository.kt` | save·findById·findByUserId(page) (abstract) |
| **domain port** | `domain/.../reconcile/port/PositionUpsertPort.kt` | `fun upsertFromKis(userId, holding)` — 본 사이클 신규 또는 T-O `PositionUpdatePort` reuse (backend-core 자율) |
| **domain port** | `domain/.../reconcile/port/KillSwitchTogglePort.kt` | `fun engage(userId, reason: KillSwitchReason)` — ADR 0043 baseline interface 추출 |
| **domain port** | `domain/.../reconcile/port/ReconcileEventPublisher.kt` | publishDrift·publishRun (Kafka 추상) |
| **domain** | `domain/.../reconcile/BalanceReconciler.kt` | 도메인 diff 로직 (수량 ≥ 1주 + 평균단가 ≥ 0.5% + 종목 누락 검출) |
| **consumer** | `consumer/.../reconcile/BalanceReconcileJob.kt` | `@Scheduled(cron="0 */5 9-15 * * MON-FRI")` + Redis 분산락 + KisRateLimiter |
| **consumer** | `consumer/.../reconcile/KisBalanceAdapter.kt` | KIS REST 호출 + 페이징 흡수 (raw KDoc 4건 LOCK §8) |
| **consumer** | `consumer/.../reconcile/KisHolding.kt` | KIS output1 매핑 (pdno·hldg_qty·pchs_avg_pric·prpr·evlu_amt 등) |
| **consumer** | `consumer/.../reconcile/KillSwitchAutoToggler.kt` | `@KafkaListener("reconcile.drift.v1")` → KillSwitchTogglePort.engage + killswitch.engaged.v1 publish |
| **consumer** | `consumer/.../reconcile/ReconcileEventKafkaPublisher.kt` | `KafkaTemplate<String, ReconcileDriftEvent>` + RunEvent (실구현) |
| **consumer** | `consumer/.../config/ReconcileTopicConfig.kt` | `reconcile.drift.v1`(30d) + `reconcile.run.v1`(7d) + `killswitch.engaged.v1`(30d) 토픽 신규 |
| **test** | `consumer/src/test/.../BalanceReconcilerTest.kt` | 5 케이스 (정상·수량 diff·평균단가 diff·종목 누락·반대 누락) |
| **test** | `consumer/src/test/.../BalanceReconcileJobTest.kt` | 3 케이스 (정상 cycle·Redis lock 충돌 skip·KIS rate limit backoff) |
| **test** | `consumer/src/test/.../KisBalanceAdapterTest.kt` | 4 케이스 (단일 페이지·다중 페이지 흡수·tr_id REAL/MOCK 분기·EGW00201 backoff) |
| **test** | `consumer/src/test/.../KillSwitchAutoTogglerTest.kt` | 3 케이스 (drift listen·duplicate skip·KillSwitch port 호출 검증) |

**Phase 1 단위 테스트 ~15건 GREEN 의무** (ADR 0068 §14-7 패턴 정합 — 자본 보호 트랙 의무 cover).

**ObjectProvider graceful fallback** (헌법 §6 + ADR 0066 패턴): Phase 2 backend-core 미합류 시 `ReconcileRunRepository`·`KillSwitchTogglePort`·`PositionUpsertPort`는 Phase 2 합류 트리거로 graceful skip + log.warn. Phase 1 단독 부팅 가능.

### 2-3. T-RECON Phase 2 산출물 매트릭스 (backend-core)

| 영역 | 파일 | 책임 |
|---|---|---|
| **Flyway** | `api/db/migration/V25__reconcile_run.sql` | reconcile_run 테이블 (IF NOT EXISTS) + INDEX(user_id, started_at DESC) |
| **Flyway** | `api/db/migration/V26__risk_event_position_drift_type.sql` (또는 슬롯 비우기) | DDL 변경 0 — 도메인 enum 변경만 (주석 포함 SELECT 1) — backend-core 자율 결정 |
| **persistence** | `api/.../persistence/reconcile/ReconcileRunJpaEntity.kt` | 6 컬럼 매핑 (Spring Data JPA — 헌법 §6 default fun 함정 회피) |
| **persistence** | `api/.../persistence/reconcile/ReconcileRunJpaRepository.kt` | `interface ... : JpaRepository<...>` (abstract만 — 헌법 §6 ADR 0051) |
| **persistence** | `api/.../persistence/reconcile/ReconcileRunRepositoryAdapter.kt` | domain port 구현 + JPA 매핑 |
| **persistence** | `api/.../persistence/killswitch/KillSwitchToggleAdapter.kt` | ADR 0043 KillSwitch state 갱신 (이미 baseline 존재 시 합류만) |
| **persistence** | `api/.../persistence/position/PositionUpsertAdapter.kt` | T-O `PositionUpdatePort` reuse 또는 별도 어댑터 (backend-core 자율) |
| **service** | `api/.../service/reconcile/ReconcileQueryService.kt` | listRuns(userId, page) + findRunDetail(id, userId 격리) |
| **service** | `api/.../service/reconcile/ReconcileManualTriggerService.kt` | userId 검증 + Kafka publish (consumer가 listen 처리) 또는 직접 호출 (backend-core 자율) |
| **controller** | `api/.../controller/reconcile/ReconcileController.kt` | REST 3 endpoint Swagger 4단 (§6) |
| **messaging** | `api/.../messaging/StompReconcileNotifier.kt` | @KafkaListener(reconcile.run.v1·reconcile.drift.v1·killswitch.engaged.v1) → STOMP /topic/recon.user.{userId} 단일 토픽 4 event |
| **test** | `api/src/test/.../ReconcileQueryServiceTest.kt` | 3 케이스 (listRuns 페이징·userId 격리·단건) |
| **test** | `api/src/test/.../ReconcileControllerTest.kt` | 4 케이스 (manual trigger·list·detail·404 userId 격리) |
| **test** | `api/src/test/.../StompReconcileNotifierTest.kt` | 3 케이스 (3 토픽 → STOMP 4 event 분기) |

**Phase 2 단위 테스트 ~10건 GREEN 의무**.

### 2-4. T-NOTIFY Phase 1 산출물 매트릭스 (backend-core)

| 영역 | 파일 | 책임 |
|---|---|---|
| **Flyway** | `api/db/migration/V27__notification_outbox.sql` | notification_outbox + system_config.notification_slack_webhook ALTER (IF NOT EXISTS) |
| **domain** | `domain/.../notification/NotificationPayload.kt` | type·priority(P0/P1/P2)·userId(masked)·reason·meta·eventId(UUID)·createdAt |
| **domain** | `domain/.../notification/NotificationPriority.kt` | enum P0·P1·P2 |
| **domain** | `domain/.../notification/NotificationStatus.kt` | enum PENDING·SENT·FAILED |
| **domain port** | `domain/.../notification/port/NotificationPort.kt` | `fun send(payload)` (abstract) |
| **domain port** | `domain/.../notification/port/NotificationOutboxRepository.kt` | save·findPending(page)·markSent·markFailed (abstract) |
| **domain port** | `domain/.../notification/port/NotificationRateLimiterPort.kt` | `fun shouldEmit(userId, topicHash, threshold, windowMin): Boolean` (Redis ZSET sliding window) |
| **domain port** | `domain/.../notification/port/SlackWebhookPort.kt` | `fun post(webhookUrl, message): SlackPostResult` (abstract) |
| **domain port** | `domain/.../notification/port/SystemConfigSlackPort.kt` | `fun fetchSlackWebhook(): String?` (system_config decryption — ADR 0046 마스터 키 정합) |
| **consumer** | `consumer/.../notification/NotificationDispatcher.kt` | 7 `@KafkaListener` (또는 6 — kis.ws.disconnected.v1 선택 graceful skip) |
| **consumer** | `consumer/.../notification/SlackWebhookSender.kt` | JDK HttpClient + Resilience4j retry 3회(1s·2s·4s) |
| **consumer** | `consumer/.../notification/NotificationFormatter.kt` | Kafka payload → 한국어 메시지 5필드 (시각 KST + 이벤트 + 사용자 마스킹 + 사유 + 즉시 액션) |
| **consumer** | `consumer/.../notification/NotificationRateLimiter.kt` | Redis ZSET sliding window 60분 |
| **consumer** | `consumer/.../notification/NotificationOutboxRetryWorker.kt` | `@Scheduled(fixedDelay = 5 * 60 * 1000)` — PENDING 행 재시도 + 24h 누적 system.notification.failed.v1 publish |
| **persistence** | `api/.../persistence/notification/NotificationOutboxJpaEntity.kt` | + JPA Repository + Adapter |
| **persistence** | `api/.../persistence/notification/SystemConfigSlackAdapter.kt` | SystemConfig 테이블 decryption (ADR 0046 AES-256-GCM 정합) |
| **test** | `consumer/src/test/.../NotificationDispatcherTest.kt` | 7 케이스 (각 토픽 listen·P0/P1/P2 분기) |
| **test** | `consumer/src/test/.../SlackWebhookSenderTest.kt` | 5 케이스 (200 OK·5xx retry·timeout·outbox INSERT·dedup) |
| **test** | `consumer/src/test/.../NotificationFormatterTest.kt` | 7 케이스 (트리거별 메시지 포맷) |
| **test** | `consumer/src/test/.../NotificationOutboxRetryWorkerTest.kt` | 3 케이스 (PENDING 재시도·24h 초과 publish·sent skip) |

**Phase 3 단위 테스트 ~18건 GREEN 의무**.

---

## §3. DB Flyway V25·V26·V27 매트릭스

ADR 0070 §2-3 정밀화. **사용자 hotfix 회피 LOCK** — V18 ADR 0042 충돌 패턴 동일.

### 3-1. V25 — reconcile_run (T-RECON Phase 2)

```sql
-- V25__reconcile_run.sql
CREATE TABLE IF NOT EXISTS reconcile_run (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL,
    started_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    finished_at TIMESTAMPTZ,
    drift_count INT NOT NULL DEFAULT 0,
    status VARCHAR(16) NOT NULL,           -- RUNNING · RECONCILED · DRIFT · DRIFT_PRICE · ERROR
    last_error TEXT,
    trigger_source VARCHAR(16) NOT NULL    -- CRON · MANUAL · API
);
CREATE INDEX IF NOT EXISTS ix_reconcile_run_user_started ON reconcile_run (user_id, started_at DESC);
CREATE INDEX IF NOT EXISTS ix_reconcile_run_status_started ON reconcile_run (status, started_at DESC);
```

### 3-2. V26 — risk_event.type=POSITION_DRIFT enum 보강 (T-RECON Phase 2)

```sql
-- V26__risk_event_position_drift_type.sql
-- DDL 변경 없음 — 도메인 enum 변경만 (RiskEventType.kt 추가).
-- Flyway 슬롯 충돌 회피 placeholder.
SELECT 1;  -- intentional no-op
```

또는 슬롯 비우고 V27로 점프 (backend-core 자율).

### 3-3. V27 — notification_outbox + system_config ALTER (T-NOTIFY Phase 1)

```sql
-- V27__notification_outbox.sql
CREATE TABLE IF NOT EXISTS notification_outbox (
    id BIGSERIAL PRIMARY KEY,
    event_id UUID NOT NULL,
    channel VARCHAR(16) NOT NULL,           -- SLACK · (FUTURE: EMAIL · SMS · PUSH · DISCORD)
    priority VARCHAR(4) NOT NULL,           -- P0 · P1 · P2
    payload JSONB NOT NULL,
    status VARCHAR(16) NOT NULL,            -- PENDING · SENT · FAILED
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    sent_at TIMESTAMPTZ,
    retry_count INT NOT NULL DEFAULT 0,
    last_error TEXT
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_notification_outbox_event_channel ON notification_outbox (event_id, channel);
CREATE INDEX IF NOT EXISTS ix_notification_outbox_status_created ON notification_outbox (status, created_at);

-- system_config 컬럼 ALTER (ADR 0046 AES-256-GCM 정합 — BYTEA 단일 row)
ALTER TABLE system_config ADD COLUMN IF NOT EXISTS notification_slack_webhook BYTEA;
```

**IF NOT EXISTS 강제 LOCK** (헌법 §6 + ADR 0068 §15-1 hotfix 회피 패턴).

---

## §4. Kafka 토픽 매트릭스 (신규 2 + listen 7)

ADR 0070 §2-4 정밀화.

### 4-1. 신규 publish (T-RECON Phase 1)

| topic | producer | consumer | retention | groupId | concurrency |
|---|---|---|---|---|---|
| `reconcile.drift.v1` | strategy-engine (BalanceReconcileJob) | strategy-engine (KillSwitchAutoToggler) · api (StompReconcileNotifier) · backend-core (NotificationDispatcher) | 30d | (consumer별 분리) | 1 |
| `reconcile.run.v1` | strategy-engine (BalanceReconcileJob) | api (StompReconcileNotifier) · backend-core (NotificationDispatcher 옵션 P2) | 7d | (consumer별 분리) | 1 |
| `killswitch.engaged.v1` | strategy-engine (KillSwitchAutoToggler) | backend-core (NotificationDispatcher) · api (STOMP fanout) | 30d | (consumer별 분리) | 1 |

**Payload 스펙 LOCK**:

```kotlin
data class ReconcileDriftEvent(
    val eventId: UUID = UUID.randomUUID(),
    val userId: Long,
    val symbol: String,
    val kisQty: Long,
    val internalQty: Long,
    val qtyDiff: Long,
    val avgPriceDiff: BigDecimal,
    val detectedAt: Instant
)

data class KillSwitchEngagedEvent(
    val eventId: UUID = UUID.randomUUID(),
    val userId: Long,
    val reason: String,              // POSITION_DRIFT · OAUTH_FAIL · MANUAL · ...
    val triggeredBy: String,         // AUTO · USER
    val engagedAt: Instant
)
```

### 4-2. NotificationDispatcher listen 7 (T-NOTIFY Phase 1)

ADR 0070 §2-4 매트릭스 동일. 7번째 `kis.ws.disconnected.v1` 선택 — ObjectProvider graceful skip 패턴(미합류 시 listener 등록 skip).

### 4-3. 7번째 토픽 graceful skip 패턴

```kotlin
// ObjectProvider 패턴 — kis.ws.disconnected.v1 토픽 정의 합류 여부에 무관하게 부팅 가능
@Component
class NotificationDispatcher(
    private val provider: ObjectProvider<KisWsDisconnectedTopicMarker>,
) {
    @KafkaListener(topics = ["kis.ws.disconnected.v1"], groupId = "magicjar-consumer-notify-ws")
    fun listenWsDisconnected(payload: KisWsDisconnectedEvent) {
        if (provider.ifAvailable == null) {
            log.warn { "kis.ws.disconnected.v1 topic marker 미합류 — listen skip" }
            return
        }
        // 정상 처리
    }
}
```

---

## §5. Redis 키 매트릭스

ADR 0070 §2-5 정합.

| Redis 키 | TTL | 용도 | sub-agent |
|---|---|---|---|
| `reconcile:lock:{userId}` | 5m | cron 동시 실행 방지 (분산락 SETNX) | strategy-engine (T-RECON Phase 1) |
| `reconcile:lastrun:{userId}` | 5m | idempotency 캐시 — 5분 내 재호출 시 직전 결과 반환 | strategy-engine (T-RECON Phase 1) |
| `position:current:{userId}` (T-O 기존) | 24h | drift 시 KIS 신뢰원으로 SET | strategy-engine (T-RECON Phase 1) |
| `notification:ratelimit:{userId}:{topicHash}` (ZSET) | 60m | P1 슬라이딩 윈도 누적 카운터 | backend-core (T-NOTIFY Phase 1) |
| `notification:dedup:{eventId}` | 24h | event_id 중복 dispatch 방지 (at-least-once 안전망) | backend-core (T-NOTIFY Phase 1) |

---

## §6. REST endpoint 매트릭스 (RECON 3 + NOTIFY 0)

ADR 0070 §2-6 정합. **Swagger 4단 LOCK** (사용자 메모리 `feedback_swagger_pattern` 정합).

### 6-1. POST /api/risk/reconcile/{userId} — 수동 trigger

```kotlin
@Tag(name = "Reconcile", description = "KIS 잔고 정합 작업 (broker reconciliation) — ADR 0041·0070")
@RestController
@RequestMapping("/api/risk/reconcile")
class ReconcileController(...) {

    @Operation(
        summary = "수동 reconcile trigger",
        description = "사용자가 즉시 1회 KIS 잔고를 가져와 내부 position과 정합 비교. 5분 내 중복 호출은 캐시 반환 (idempotency)."
    )
    @ApiResponses(...)
    @PostMapping("/{userId}")
    fun triggerManualReconcile(
        @Parameter(description = "대상 사용자 ID (multi-tenant 격리)", example = "12345678")
        @PathVariable userId: Long
    ): ResponseEntity<ReconcileRunDto> = ...
}

@Schema(description = "reconcile 실행 기록")
data class ReconcileRunDto(
    @Schema(description = "실행 ID", example = "1001") val id: Long,
    @Schema(description = "실행 시작 시각 (KST)", example = "2026-05-13T14:00:00+09:00") val startedAt: Instant,
    @Schema(description = "실행 종료 시각 (RUNNING 일 때 null)", example = "2026-05-13T14:00:02+09:00") val finishedAt: Instant?,
    @Schema(description = "drift 검출 종목 수 (0이면 정합)", example = "2") val driftCount: Int,
    @Schema(description = "상태", example = "DRIFT") val status: String,  // RUNNING · RECONCILED · DRIFT · DRIFT_PRICE · ERROR
    @Schema(description = "오류 메시지 (status=ERROR일 때만)", example = "EGW00201 (TPS 초과)") val lastError: String?
)
```

### 6-2. GET /api/risk/reconcile/runs

```kotlin
@Operation(
    summary = "reconcile 실행 이력 조회",
    description = "사용자별 reconcile_run 시계열 (페이징). 최신순 정렬."
)
@GetMapping("/runs")
fun listRuns(
    @Parameter(description = "사용자 ID", example = "12345678") @RequestParam userId: Long,
    @Parameter(description = "페이지 (0-based)", example = "0") @RequestParam(defaultValue = "0") page: Int,
    @Parameter(description = "페이지당 row 수", example = "20") @RequestParam(defaultValue = "20") size: Int,
): Page<ReconcileRunDto> = ...
```

### 6-3. GET /api/risk/reconcile/runs/{id}

```kotlin
@Operation(
    summary = "reconcile 단건 상세",
    description = "drift_count + last_error + 종목별 diff 상세 (HoldingDiffDto 배열)."
)
@GetMapping("/runs/{id}")
fun findDetail(
    @Parameter(description = "실행 ID", example = "1001") @PathVariable id: Long,
    @Parameter(description = "사용자 ID (격리 검증)", example = "12345678") @RequestParam userId: Long,
): ReconcileRunDetailDto = ...
```

**userId mismatch 시 404 LOCK** (ADR 0010 multi-tenant 격리 정합).

### 6-4. T-NOTIFY REST 0 LOCK

Slack은 outbound only. system_config.notification_slack_webhook 갱신은 **SettingsModal SystemConfigSection** (ADR 0069 §3) + 기존 `PUT /api/system-config` (ADR 0046 baseline)에서 cover. 본 사이클 신규 endpoint 0.

---

## §7. STOMP 토픽 매트릭스

ADR 0070 §2-7 정밀화.

### 7-1. /topic/recon.user.{userId} — 단일 토픽 + event discriminator

| eventType | payload 핵심 | 트리거 |
|---|---|---|
| `reconcile.run.started` | `runId`·`startedAt`·`triggerSource` | Kafka `reconcile.run.v1` (status=RUNNING) |
| `reconcile.run.completed` | `runId`·`finishedAt`·`status`·`driftCount` | Kafka `reconcile.run.v1` (status ∈ RECONCILED·DRIFT·...) |
| `reconcile.drift.detected` | `runId`·`symbol`·`kisQty`·`internalQty`·`qtyDiff` | Kafka `reconcile.drift.v1` |
| `killswitch.auto.engaged` | `reason`·`triggeredBy`·`engagedAt` | Kafka `killswitch.engaged.v1` (triggeredBy=AUTO) |

### 7-2. /topic/order.user.{userId} (기존 T-O)

변경 0 — ADR 0068 baseline 유지.

### 7-3. /topic/risk.user.{userId} (기존 T-RISK)

변경 0 — ADR 0067 baseline 유지.

---

## §8. KIS-first raw 인용 매트릭스 (헌법 §3 의무)

ADR 0070 §2-2 정합. **자체 추론 0 + raw URL inline KDoc LOCK**.

### 8-1. 인용 4건

| # | tr_id | endpoint | raw URL · 라인 | KisBalanceAdapter 사용처 |
|---|---|---|---|---|
| 1 | `TTTC8434R` (실전) | `/uapi/domestic-stock/v1/trading/inquire-balance` | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py` L18·L97~100·L102~112·L125~134 | 메인 |
| 2 | `VTTC8434R` (모의) | (동일) | (동일) | MOCK 분기 |
| 3 | `CTRP6548R` | `/uapi/domestic-stock/v1/trading/inquire-account-balance` | `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_account_balance/inquire_account_balance.py` L14·L16·L42·L56~60 | 보조 (계좌 단위 fast check — output2 단건) |
| 4 | `H0STCNI0`/`H0STCNI9` | KIS WS (T-O baseline) | ADR 0068 §14-6 cross-ref | T-RECON 직접 무관 — cross-ref만 |

### 8-2. KisBalanceAdapter inline KDoc 의무 LOCK

```kotlin
/**
 * KIS 도메인 잔고 조회 어댑터 — T-RECON Phase 1 (ADR 0041·0070).
 *
 * raw 정합 (헌법 §3 의무):
 * - 실전 매수가능 잔고: tr_id = TTTC8434R
 *   URL: /uapi/domestic-stock/v1/trading/inquire-balance
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_balance/inquire_balance.py
 *   L18 (API_URL) · L97~100 (TR_ID assignment) · L102~112 (parameter mapping) · L125~134 (pagination loop)
 * - 모의 매수가능 잔고: tr_id = VTTC8434R (동일 endpoint, tr_id만 분기)
 * - 계좌자산현황 (보조): tr_id = CTRP6548R
 *   URL: /uapi/domestic-stock/v1/trading/inquire-account-balance
 *   raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_account_balance/inquire_account_balance.py
 *   L14·L16·L42·L56~60
 *
 * 페이징 룰 (raw L125~134 정합):
 * - tr_cont 헤더 "M" or "" → 다음 페이지 존재 (CTX_AREA_FK100·NK100 전달)
 * - tr_cont 헤더 "F" or "D" → 마지막 페이지 (loop 종료)
 * - 실전 50건/요청 · 모의 20건/요청 한도
 *
 * Rate Limit (ADR 0037 정합):
 * - KisRateLimiterPort.acquire() 통과 의무 (batch share 3/10)
 * - EGW00201 (TPS 초과) → 1초 backoff + 1회 재시도
 *
 * AI 호출 0 (ADR 0023 §2-4 + 룰 5):
 * - 룰/cron 기반, AI provider import 0.
 */
@Component
class KisBalanceAdapter(...) : KisBalancePort {
    override fun fetchAllBalances(userId: Long): List<KisHolding> = ...
}
```

---

## §9. UI 변경 매트릭스 (frontend 0 LOCK)

ADR 0070 §2-8 정합.

| UI 영역 | 변경 | 사유 |
|---|---|---|
| SettingsModal SystemConfigSection | **0** | Slack webhook 입력 baseline reuse (ADR 0069 §3) — useUpdateSystemConfig 자동 |
| AppHeader ⚙️ 진입 | **0** | baseline reuse |
| KillSwitch 토글 (사용자 수동) | **0** | KillSwitch state 갱신은 backend AutoToggler가 자동 toggle (ADR 0043). 사용자 수동 UI는 backlog §N 후속 |
| Risk 페이지 reconcile timeline | **0 (후속 backlog)** | T-RECON Phase 2 마감 후 frontend 단발 디스패치 — STOMP /topic/recon.user.{userId} listen + reconcile_run timeline + drift_holdings 표 |
| Slack push 모바일 | **0 (Slack 앱)** | Slack 자체 push 사용 — magicJar UI 무관 |

**ADR 0069 §3 SettingsModal SystemConfigSection LOCK reuse**: 본 사이클 신규 frontend 파일 0. 후속 backlog §N (Risk 페이지 reconcile timeline)는 v3.6.3 또는 v3.7.0 사이클 frontend 단발 디스패치.

---

## §10. sub-agent 디스패치 매트릭스 (직렬 LOCK)

### 10-1. 디스패치 순서

1. **T-RECON Phase 1** — strategy-engine (단일 sub-agent)
   - 브랜치: `agent/strategy/t-recon-phase1`
   - 산출물: §2-2 매트릭스 (~20 파일)
   - 단위 테스트: ~15건 GREEN
   - PR 본문 LOCK: KIS-first raw 4건 inline KDoc evidence + AI 호출 0 grep evidence + Phase 2 graceful fallback 설명
   - Slot anchor: `<!-- pr-392-slot-strategy-engine -->`

2. **T-RECON Phase 2** — backend-core (Phase 1 머지 후)
   - 브랜치: `agent/backend-core/t-recon-phase2`
   - 산출물: §2-3 매트릭스 (~13 파일)
   - 단위 테스트: ~10건 GREEN
   - PR 본문 LOCK: Flyway V25·V26 IF NOT EXISTS evidence + Swagger 4단 한국어 + example evidence + REST 3 endpoint OpenAPI YAML evidence
   - Slot anchor: `<!-- pr-392-slot-backend-core-recon -->`

3. **T-NOTIFY Phase 1** — backend-core (Phase 2 머지 후)
   - 브랜치: `agent/backend-core/t-notify-phase1`
   - 산출물: §2-4 매트릭스 (~20 파일)
   - 단위 테스트: ~18건 GREEN
   - PR 본문 LOCK: Flyway V27 IF NOT EXISTS evidence + Slack webhook AES-256-GCM 정합 (ADR 0046) + 7 토픽 listen evidence + outbox PENDING retry 5분 cycle evidence
   - Slot anchor: `<!-- pr-392-slot-backend-core-notify -->`

### 10-2. frontend 0 LOCK

본 사이클 frontend sub-agent 디스패치 0. Slot anchor 0건.

### 10-3. qa sub-agent

사용자 메모리 `feedback_qa_on_demand` + `feedback_dev_priority_qa_batch` 정합 — 본 사이클 qa 디스패치 0. W3 완전 마감(v3.7.0 cut) 후 누적 일괄 qa 의제 권장 (다중 트랙 연관 검증).

### 10-4. CHANGELOG slot 발행

본 architect spec PR (#392) 단일에 slot anchor 3개 + TBD row 3개 prepend (사용자 메모리 `feedback_spec_plan_scope` 정합 — 신규 트랙·다중 ADR이므로 slot 발행 의무. chore PR 분리 LOCK 비용 회피 — 사이클이 작아 architect spec PR 단일에 포함 권장).

LOCK:
- `<!-- pr-392-slot-architect -->` (본 PR 산출물 라인)
- `<!-- pr-392-slot-strategy-engine -->` (TBD)
- `<!-- pr-392-slot-backend-core-recon -->` (TBD)
- `<!-- pr-392-slot-backend-core-notify -->` (TBD)
- `<!-- pr-392-slot-end -->`

---

## §11. 검증 시나리오 (full chain LOCK)

### 11-1. 시나리오 1 — 정상 흐름 (drift 0)

| step | 행동 | evidence |
|---|---|---|
| 1 | 5분 cron fire | log: `BalanceReconcileJob fires for userId=N` |
| 2 | Redis lock acquire | Redis `reconcile:lock:{userId}` set with TTL 5m |
| 3 | KIS REST 호출 | log: `tr_id=TTTC8434R, page=1` + KIS 200 OK |
| 4 | diff 계산 | log: `0 holdings diff, status=RECONCILED` |
| 5 | reconcile_run INSERT | DB row status=RECONCILED, drift_count=0 |
| 6 | Kafka publish | `reconcile.run.v1` payload status=RECONCILED |
| 7 | Redis lock release | (TTL 5m fallback OK) |
| 8 | STOMP fanout | `/topic/recon.user.{userId}` eventType=reconcile.run.completed |

### 11-2. 시나리오 2 — drift 검출 full chain (P0)

| step | 행동 | evidence |
|---|---|---|
| 1 | KIS 잔고 → 005930 100주 | KIS REST output1 |
| 2 | 내부 position → 005930 99주 | DB select |
| 3 | diff = 1주 (≥ 1주 임계) | log: `DRIFT: 005930, kis=100, internal=99` |
| 4 | DB position.qty = 100 (KIS 신뢰원) | DB upsert |
| 5 | Redis `position:current:{userId}` SET | Redis HGET 검증 |
| 6 | Kafka `reconcile.drift.v1` publish | Kafka consumer offset 검증 |
| 7 | KillSwitchAutoToggler listen | log: `KillSwitch ENGAGE: reason=POSITION_DRIFT` |
| 8 | kill_switch_state.enabled = true | DB select |
| 9 | Kafka `killswitch.engaged.v1` publish | Kafka offset 검증 |
| 10 | NotificationDispatcher listen | log: `Slack POST P0 immediate` |
| 11 | Slack POST 200 OK | outbox row status=SENT |
| 12 | Slack 메시지 도착 (사용자 모바일) | Slack 앱 inbound 검증 |
| 13 | STOMP /topic/recon.user.{userId} | useStompReconcile listen (frontend 후속) |

**검증 의무** (사용자 메모리 `feedback_verification_strict` 정합): sub-agent PR 본문에 위 13 step 전체 evidence (log line·DB select·Redis HGET·Kafka offset·Slack 메시지 스크린샷) 인용 의무. GREEN 도장만으로 "완료" 종결 금지.

### 11-3. 시나리오 3 — Slack 5xx outbox fallback

| step | 행동 | evidence |
|---|---|---|
| 1 | Slack POST → 503 | log: `Slack 5xx, retry 1/3` |
| 2 | Resilience4j 1s backoff retry | log: 1s 후 재시도 |
| 3 | 2회 추가 retry 모두 503 | log: `retry exhausted` |
| 4 | outbox row INSERT status=PENDING | DB row select |
| 5 | 5분 후 RetryWorker fire | `@Scheduled` |
| 6 | PENDING row select + Slack POST | log: `outbox retry: 1 row` |
| 7 | 200 OK → status=SENT | DB row update |

### 11-4. 시나리오 4 — manual trigger idempotency

| step | 행동 | evidence |
|---|---|---|
| 1 | POST /api/risk/reconcile/{userId} | 202 Accepted |
| 2 | 5분 내 동일 사용자 재호출 | Redis `reconcile:lastrun:{userId}` 캐시 hit |
| 3 | 직전 결과 반환 (신규 cycle 0) | log: `idempotency cache hit` |

### 11-5. 시나리오 5 — fitness 검증

| 검증 | evidence |
|---|---|
| AI 호출 0 | `grep -rn "import.*anthropic\|import.*openai\|ChatClient\|springframework\.ai" consumer/reconcile consumer/notification domain` 0 lines |
| KIS-first raw inline | KisBalanceAdapter.kt KDoc 4건 raw URL 인용 |
| mock 합성 0 | KIS REST 실제 응답 또는 "잔고 0건" 명시 |
| multi-tenant 격리 | userId 격리 — POST /api/risk/reconcile/9999 (타 사용자) → 404 |

---

## §12. backlog 흡수 매트릭스

본 사이클 backlog §N 신규 등록 의제:

| § | 의제 | 우선도 | 트리거 |
|---|---|---|---|
| §46 (가칭) | Risk 페이지 reconcile timeline UI + KillSwitch 토글 UX 보강 | M | T-RECON Phase 2 마감 후 |
| §47 (가칭) | drift 임계치 사용자 설정화 (수량 1주·평균단가 0.5% 고정 → 사용자 토글) | L | 본격 운영 사용자 의제 발견 시 |
| §48 (가칭) | MarketSessionFsm cron skip 통합 (시장 휴장 시 reconcile cron skip) | L | 사용자 운영 의제 시 |
| §49 (가칭) | user별 Slack webhook 분리 (multi-user 본격화 시) | L | multi-user 실사용자 합류 시 |
| §50 (가칭) | Discord·이메일·SMS 보조 채널 (Slack 보완) | L | post-MVP |
| §51 (가칭) | system.startup.v1·system.shutdown.v1 publish 합류 (ADR 0045 Graceful Shutdown 트랙) | M | T-RECON·T-NOTIFY 마감 후 횡단 트랙 |

---

## §13. 사용자 메모리 정합 (의무 검증)

| 메모리 | 정합 |
|---|---|
| `feedback_v3_skepticism_no_regression` | fitness gap lens — §1-3 ★★★ 검증 |
| `feedback_kis_first_policy` | KIS-first raw 4건 inline KDoc §8 LOCK |
| `feedback_3tier_hierarchy` | architect spec → strategy-engine → backend-core 직렬 §10 |
| `feedback_no_mock_data` | mock 합성 0 — KIS REST 실제 응답 §11-5 |
| `feedback_release_policy_patch_first` | v3.6.2 patch 사이클 — minor cut은 W3 완전 마감 후 v3.7.0 |
| `feedback_dev_priority_qa_batch` | qa 디스패치 0 — W3 완전 마감 후 누적 일괄 §10-3 |
| `feedback_swagger_pattern` | REST 3 endpoint Swagger 4단 한국어 + example §6 LOCK |
| `feedback_verification_strict` | sub-agent PR 본문에 13 step evidence 인용 의무 §11-2 |
| `feedback_honorific` | 본 문서 + sub-agent 커밋 본문 존댓말 |
| `feedback_kis_key_realonly_for_data` | 정보 수집(잔고)은 REAL 키 — MAGICJAR_MODE 분기는 주문 액션만 (T-O 기존) |
| `feedback_orchestrator_active_parallel` | 본 사이클은 자본 보호 path 직렬 LOCK (T-NOTIFY가 T-RECON 토픽 의존) — 병렬 X |
| `feedback_no_github_actions` | release tag/brunch는 W3 완전 마감 후 v3.7.0 cut에서. 본 사이클 GHA workflow 0 |
| `project_system_vision` | 룰 본체 자본 보호 — AI 호출 0 grep evidence §11-5 |

---

## §14. 영향 ADR/문서 매트릭스

### 14-1. ADR 정합 갱신

- **ADR 0041 §5 변경 이력** — 2026-05-13 row 추가 (`reconcile.drift.v1` 명명 보강 + ADR 0070 정밀 명세 cross-ref)
- **ADR 0044 §5 변경 이력** — 2026-05-13 row 추가 (`killswitch.engaged.v1` 신규 listen + ADR 0070 정밀 명세 cross-ref)
- **ADR README §10-1·§10-2** — 0070 row 추가 + 헤더 count 66건
- **ADR README §11** — 후속 ADR 번호 0071로 시프트

### 14-2. master-architecture 정합 갱신

- **§10 ADR 인덱스** — 0070 row 추가 + 헤더 (`0001~0057 Accepted + 0061·0062·0063·0066·0067·0068·0069·0070 Accepted`)
- **§C.12** — T-O 마감 후 T-RECON·T-NOTIFY 합류 표기 갱신
- **§C.13** — T-RECON·T-NOTIFY 흐름 표기 보강
- **§3-1 부수 흐름** — Balance Reconcile + Notification 정밀화 cross-ref (ADR 0041·0044·0070)
- **§6 Kafka topic 매트릭스** — `reconcile.drift.v1`·`reconcile.run.v1`·`killswitch.engaged.v1` 토픽 갱신

### 14-3. tracks-roadmap 정합 갱신

- **§2 트랙 목록** — T-RECON·T-NOTIFY row 갱신 (ADR 0070 cross-ref + Phase 분리 LOCK)
- **§3 T-RECON outline** — 산출물 매트릭스 prepend (ADR 0070 §2-2)
- **§3 T-NOTIFY outline** — 산출물 매트릭스 prepend (ADR 0070 §2-4)
- **§8 변경 이력** — 2026-05-13 row 추가

### 14-4. backlog 정합

- **§46~§51 신규 등록** (§12 매트릭스)

### 14-5. CHANGELOG slot

- 본 PR architect row 1건 + sub-agent TBD slot 3건 (`<!-- pr-392-slot-strategy-engine -->`·`<!-- pr-392-slot-backend-core-recon -->`·`<!-- pr-392-slot-backend-core-notify -->`)

---

## §15. Rollback 시나리오

| 시나리오 | 대응 |
|---|---|
| KillSwitch auto-ON 후 사용자 잔고 일치 확인 | 사용자 수동 OFF 토글 (ADR 0043 baseline) → 60초 cooldown 후 매매 재개 |
| 평균단가 diff 0.5% 노이즈 false positive | drift_count 누적 모니터링 → 1시간 ≥ 3회 시 임계치 조정 backlog §47 의제 진입 |
| Slack webhook 등록 안 한 사용자 | `system_config.notification_slack_webhook IS NULL` → NotificationDispatcher graceful skip + log.warn (P0 alert는 STOMP만) |
| KIS API 401 누적 | 사용자별 5분 3회 → KillSwitch auto-ON (ADR 0043 trigger 정합) + Slack P0 (kis.token.expired.v1 — 본 사이클 선택 publish, ADR 0044 baseline) |
| Redis Cluster 일시 장애 | `reconcile:lock` 미설정 시 cron 2회 fire risk → 대신 idempotency `reconcile:lastrun:{userId}` 캐시 fallback (5분 내 재호출 시 직전 결과). 단 KIS RateLimiter는 acquire 무관하게 적용 |
| Flyway V25·V27 마이그레이션 실패 | IF NOT EXISTS LOCK으로 충돌 회피 — 직접 실패 시 backend-core hotfix 사이클 (V18 ADR 0042 패턴 정합) |
| 자본 사고 누적 (drift 1일 ≥ 10회) | architect 긴급 ADR 진입 + KillSwitch permanent 사용자 알림 |

---

## §16. 본 spec 본문 완결성 검증

| 항목 | 충족 |
|---|---|
| §1 묶음 사유 + fitness 검증 | ✓ |
| §2 Phase 분리 LOCK + 산출물 매트릭스 3 Phase | ✓ |
| §3 DB Flyway V25·V26·V27 DDL 전수 | ✓ |
| §4 Kafka 토픽 신규 2 + listen 7 + payload 스펙 | ✓ |
| §5 Redis 키 매트릭스 5건 | ✓ |
| §6 REST 3 endpoint Swagger 4단 한국어 + example | ✓ |
| §7 STOMP 토픽 + event discriminator 4종 | ✓ |
| §8 KIS-first raw 4건 inline KDoc LOCK | ✓ |
| §9 UI 변경 매트릭스 (frontend 0 LOCK) | ✓ |
| §10 sub-agent 직렬 디스패치 매트릭스 | ✓ |
| §11 검증 시나리오 5건 + 13 step evidence LOCK | ✓ |
| §12 backlog §46~§51 흡수 6건 | ✓ |
| §13 사용자 메모리 정합 13건 | ✓ |
| §14 영향 ADR/문서 매트릭스 5섹션 | ✓ |
| §15 Rollback 시나리오 7건 | ✓ |

---

> **본 spec 권위**: ADR 0070 본문 + ADR 0041·0044 baseline 정합. 본 spec 본문은 sub-agent 디스패치 정밀 명세 권위 — Phase 1·2·3 LOCK + 산출물 매트릭스 LOCK + 검증 evidence 의무 LOCK.

# ADR 0042 — Position Bootstrap & Recovery (앱 시작·WS 재연결 시 잔고 복구)

- 상태: Accepted
- 작성일: 2026-05-07
- 결정자: architect (사용자 위임 + orchestrator dispatch · Q2=A Critical 7건 중 2)
- 관계: ADR 0011 보강(주문 실행기) · ADR 0024 보강(Position) · ADR 0041 정합(Reconciliation) · ADR 0014 정합(Fallback) · ADR 0028 정합(Watchlist Ingestion)

---

## 1. 컨텍스트 (왜 이 결정이 필요한가)

ADR 0024 §2-3은 **"KIS WS H0STCNI0 체결통보 → consumer Position 갱신 + Redis `position:current:{userId}` (TTL 30초)"** 만 정의한다. 그러나 다음 2가지 시점이 누락:

1. **앱 부팅 직후** — consumer JVM이 막 떴을 때 Redis `position:current:{userId}`는 비어있고 DB `position`도 마지막 종료 시점 snapshot. **사용자가 KIS HTS·MTS·다른 채널로 매매한 흔적 미반영** 상태.
2. **WS 재연결 직후** — WS disconnect ↔ reconnect 사이에 발생한 체결은 우리가 못 받음. drift 발생.

ADR 0041 BalanceReconcileJob이 5분 cron으로 catch-up하지만 **첫 5분간 시그널 → 주문이 잘못된 position 기준으로 발사** 가능. PositionLimitGuard도 잘못된 base로 무력화.

또한 **TTL 30초**는 너무 짧다 — WS tick이 30초 이상 멈추면 Redis 만료되고 fallback 부재.

**필요한 결정**:
- 부팅 시 무조건 KIS REST 잔고 → DB → Redis 워밍 후 WS 구독 시작
- WS 재연결 시 gap 보정 (미수신 체결 fetch)
- Redis TTL 정책 재검토

## 2. 결정

### 2-1. 핵심 명세

**3 단계 Bootstrap + 1 단계 Recovery**.

#### Bootstrap (consumer 부팅 시 1회)

```kotlin
@Component
class PositionBootstrapRunner(
    private val userRepository: UserRepository,
    private val kisBalancePort: KisBalancePort,
    private val positionRepository: PositionRepository,
    private val positionCachePort: PositionCachePort,    // Redis
    private val kisWsSubscriber: KisWsSubscriber,
    private val rateLimiter: KisRateLimiterPort,
) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        userRepository.findActiveTradingUsers().forEach { user ->
            // STAGE 0-1: KIS REST 잔고 조회
            rateLimiter.acquire()
            val kisBalance = kisBalancePort.fetchBalance(user.id)

            // STAGE 0-2: DB position upsert
            positionRepository.upsertAll(user.id, kisBalance.holdings)

            // STAGE 0-3: Redis position:current:{userId} SET (TTL 24h)
            positionCachePort.put(user.id, kisBalance.holdings, ttl = Duration.ofHours(24))

            // STAGE 0-4: WS 구독 시작 (체결통보 + 호가)
            kisWsSubscriber.subscribeUser(user.id)
        }
    }
}
```

**활성 사용자 0명 OK** — idle 가동, 첫 사용자 등록 시 EventListener 트리거.

#### Recovery (WS reconnect 시)

```kotlin
@Component
class PositionRecoveryListener(
    private val kisDailyExecutionPort: KisDailyExecutionPort,
    private val positionRepository: PositionRepository,
    private val positionCachePort: PositionCachePort,
    private val rateLimiter: KisRateLimiterPort,
) {

    @EventListener
    fun onWsReconnected(event: KisWsReconnectedEvent) {
        val userId = event.userId
        val lastKnownAt = positionCachePort.getLastUpdatedAt(userId)
            ?: Instant.now().minusSeconds(60)

        rateLimiter.acquire()
        val missedExecs = kisDailyExecutionPort.fetchExecutions(
            userId = userId,
            from = lastKnownAt,
            to = Instant.now(),
            executionStatus = ALL,    // 체결·미체결 모두
        )

        missedExecs.forEach { exec ->
            positionRepository.applyExecution(userId, exec)
            positionCachePort.applyExecution(userId, exec)
        }
    }
}
```

### 2-2. KIS endpoint (open-trading-api 정합)

| 단계 | 명칭 | endpoint | TR_ID 실전 | TR_ID 모의 | 출처 |
|---|---|---|---|---|---|
| Bootstrap STAGE 0-1 | 주식잔고조회 | `/uapi/domestic-stock/v1/trading/inquire-balance` | `TTTC8434R` | `VTTC8434R` | `examples_llm/.../inquire_balance.py` |
| Recovery (3개월 이내) | 일별주문체결조회 | `/uapi/domestic-stock/v1/trading/inquire-daily-ccld` | `TTTC0081R` | `VTTC0081R` | `examples_llm/.../inquire_daily_ccld.py` |
| Recovery (3개월 초과) | 일별주문체결조회 | 동일 | `CTSC9215R` | `VTSC9215R` | 동 |

**Recovery 페이지 한도**: 100건/요청(실전), 15건/요청(모의). 페이징 의무.

`docs/operations/kis-api-reference.md` §2 후속 등재 필요 (`inquire-daily-ccld`).

### 2-3. Redis TTL 정책 재검토

ADR 0024 §2-3 기존 TTL 30초는 **WS tick 끊기면 cache miss**. 본 ADR로 변경:

| key | 기존 TTL | 신규 TTL | 사유 |
|---|---|---|---|
| `position:current:{userId}` | 30초 | **24시간 + 매 체결 시 refresh** | bootstrap이 신뢰할 수 있는 base를 보장 |
| `position:lastUpdatedAt:{userId}` (신규) | n/a | 7일 | recovery에서 last known timestamp 조회용 |

**RealKisRestAdapter.fetchPosition fallback** — Redis miss 시 ADR 0014 fallback chain (live → cache → last DB row) 정합.

### 2-4. master-architecture §3-1 데이터 흐름 갱신

기존 `[1] InstrumentSyncJob` 앞에 **`[0] Position Bootstrap`** 단계 prepend:

```
[0] Position Bootstrap (consumer ApplicationRunner)
    → 활성 user 순회 → KIS REST 잔고 → DB upsert → Redis 워밍 → WS 구독 시작
    → 이후 본체 9 단계 정상 동작 보장
       ↓
[1] InstrumentSyncJob (cron 17:00) → instrument 테이블 갱신
       ↓
... (이하 동일)
```

WS 재연결 hook은 9 단계 사이클 외 부수 흐름 — §3-1 표 하단 별도 섹션으로 명시.

### 2-5. multi-tenant / 정합

| 항목 | 정책 |
|---|---|
| 활성 사용자 정의 | `users.trading_mode IN (MOCK_KIS, REAL_KIS) AND kis_app_key IS NOT NULL` |
| 부팅 직렬 vs 병렬 | 직렬 (rate limit 안전) — N명 × 1~2초 ≈ 30초 이내 |
| 부팅 실패 시 | 해당 user만 KillSwitch auto-ON (ADR 0043 trigger 정합) + 다른 사용자 부팅 계속 |
| 첫 사용자 등록 시 | `UserRegisteredEvent` listener → bootstrap 위임 (재시작 없이) |
| KIS 키 무효 시 | 401 → 1회 재발급 → 그래도 실패 시 KillSwitch ON + Slack alert |

## 3. 결과·영향

### 3-1. 트랙 영향

- **T-W1 (W1)** — bootstrap 흐름 의무 합류 (사용자 등록 → KIS 키 입력 → 부팅 hook).
- **T-O (W3)** — OrderExecutor 시작 전 PositionBootstrap 통과 의무.
- **T-RISK (W3)** — PositionLimitGuard가 신뢰할 수 있는 base 보장 (본 ADR 의무).

### 3-2. 컨테이너 영향

| 컨테이너 | 영향 |
|---|---|
| `consumer` | `PositionBootstrapRunner` (ApplicationRunner) + `PositionRecoveryListener` (EventListener) |
| `domain` | `KisBalancePort`(이미 ADR 0041) + `KisDailyExecutionPort`(신규) + `PositionCachePort.getLastUpdatedAt` 추가 |
| `api` | `POST /api/position/bootstrap/{userId}` manual trigger (선택, 디버그용) |

### 3-3. 운영 영향

| 모니터링 | 임계 |
|---|---|
| 부팅 시간 | user 1명당 ≤ 2초. 10명이면 ≤ 20초. 초과 시 alert. |
| WS reconnect 빈도 | 1시간에 사용자별 ≥ 3회면 KIS WS 연결 품질 점검 |
| Recovery missed_count | 사용자당 1회 reconnect에 ≥ 10건 누락 → 알림 + KillSwitch 검토 |

### 3-4. 트레이드오프

| 검토안 | 채택 여부 | 사유 |
|---|---|---|
| (a) **부팅 시 자동 위임 안 함** (사용자 manual trigger only) | 폐기 | 사용자 잊으면 사고 — 자동이 안전 |
| (b) **부팅 시 WS 먼저 → REST nice-to-have** | 폐기 | WS 첫 구독은 새 체결만 받음 — 기존 보유 종목 base 누락 |
| (c) **REST → DB → Redis → WS 순차** (채택) | ✅ | base 확보 후 실시간 합류 — 표준 패턴 |

## 4. 후속 ADR 후보

- **0048 (가칭)** — Distributed Bootstrap (consumer JVM N개 scale-out 시 user 분할 처리)
- **0049 (가칭)** — Cold start 우선순위 (Watchlist top-N 종목 WS 우선 구독)

## 5. 변경 이력

| 날짜 | 변경 |
|---|---|
| 2026-05-07 | ADR 0042 신규 발행 — Critical fitness 갭 2번. Q2=A 사용자 결정 정합. (architect) |

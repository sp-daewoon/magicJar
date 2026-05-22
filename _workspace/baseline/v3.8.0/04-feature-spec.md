# v3.8.0 기능 명세 (트랙별 표준 9 항목)

- baseline: v3.8.0
- 작성일: 2026-05-22
- 작성자: backend-core (bot+backend-core@magicjar.local)
- SoT: `docs/architecture/master-architecture.md` + ADR INDEX (`_workspace/arch/adr/README.md`) + ADR 0094 (운영 단계 transition)
- 기준: v3.7.0 04-feature-spec.md 전체 이월 + 운영 단계 진입(ADR 0094) + 신규 트랙 0건 + 신규 ADR 6건(0091·0092·0093·0094·0095·0096) + 운영 인프라 6건(P0-OPS-1~6) + 자본 path 보강(KillSwitch race-safe·KIS_TPS LIVE fix·SystemEvent emit 5건)

---

## 🔄 v3.7.0 → v3.8.0 기능 매트릭스 진화 요약 (리팩토링맨 lens)

| 트랙 | v3.7.0 | v3.8.0 변경 |
|------|--------|------------|
| T-O 주문 실행 | 5 endpoint + 12 step + 3중 게이트 + Outbox publish + event_id 멱등 | **+ KillSwitch race-safe guard A+B+C (Step 4)** + **EGW00201 1초 backoff** + **stomp_event qty/executedQty 정확 매핑** + **SystemEvent emit 5 point** |
| T-RECON | v3.6.2 baseline + persistence-shared 이관 | **+ DRIFT_DETECTED SystemEvent emit (BalanceReconcileService P1)** |
| T-RISK | KillSwitch chain (ADR 0024·0043·0070) | **+ A+B+C race-safe guard (pending+grace+strike)** — PR #648 ADR 0043·0070 amend |
| T-IND 지표 | indicator-impl 모듈 (ADR 0083) | v3.7.0 LOCK 이월 |
| T-PAT 패턴 | pattern-engine 모듈 (ADR 0054) | v3.7.0 LOCK 이월 |
| **T-SIG·T-DSL strategy-engine** | consumer-only (api는 HOLD stub) | **strategy-engine 모듈 분리** — api·consumer 공유 (ADR 0092 — HOLD stub P0 결함 차단) |
| T-BT 백테스트 | api 단독 HOLD stub risk | **strategy-engine 실 evaluator 직결** (ADR 0092) |
| T-SC 스크리너 | api 단독 HOLD stub risk | **strategy-engine 실 evaluator 직결** (ADR 0092) |
| T-NOTIFY | v3.6.2 baseline + persistence-shared 이관 | **+ Alert routing P1·P2·P3 매트릭스** (ADR 0095) |
| **T-CC 캔들 정합 NXT** | KOSPI=UN/KOSDAQ=J market-level | **종목 단위 resolver** (ADR 0091 — KOSPI200·KOSDAQ150 allow-list + UN→J fallback + 30% 휴리스틱 + stale 30분) — qa 첫 임무 hotfix |
| **T-CC LIVE TPS fix** | Bucket4j 10 req/s baseline | **+ 200ms sleep 마진 + H1 페이지 burst 완화 + EGW00201 1초 backoff + priority=BACKFILL 분리** (PR #571·#658·#659) |
| T-LWC 차트 lazy backfill | 600봉 cap (MAX_PAGES=6) | **ADR 0093 Proposed** — Lazy backfill + 사용자 입력(300/600/1200/3000) + timeframe 매트릭스 (frontend 합류 대기) |
| **T-OPS 운영 인프라** ★ | 0건 (운영 헌법 §9 메모만) | **★ v3.8.0 신규 트랙 — P0 OPS 6건 풀세트 인수 (backend-core)** |
| **T-QA Visual** ★ | qa 1건 (past 7w) | **★ v3.8.0 신규 트랙 — Playwright + 스크린샷 + 시각 회귀 5 시나리오 baseline + 첫 실전 임무 (NXT stale)** (ADR 0094) |
| T-AI 옵션 분석 | W4 진입 대기 | **v3.8.0 LOCK 이월** — 운영 단계 진입 직전 (사용자 결재 의존) |
| T-ACCT 잔액 | KIS TTTC8434R/VTTC8434R + V31 realized_pnl | v3.7.0 LOCK 이월 |
| T-MAN 수동 주문 | source='MANUAL' + V37·V38 | v3.7.0 LOCK 이월 |

✨ **트랙 수**: v3.7.0 14 트랙 → v3.8.0 **16 트랙** (T-OPS 운영 인프라 + T-QA Visual 합류). 핵심 변화는 **트랙 추가가 아닌 운영 단계 진입 자체** — 기존 트랙의 SLA·alert·DR·runbook·회귀 자동화 정합.

---

## 표준 항목 구조

각 트랙은 아래 9 항목으로 기술합니다.

1. **REST** — 노출 엔드포인트
2. **도메인** — 핵심 domain 클래스 + 포트 인터페이스
3. **영속** — JPA 엔티티 + Flyway 마이그레이션
4. **Kafka** — 발행/소비 토픽
5. **UI** — 프론트엔드 페이지/컴포넌트
6. **PR** — 관련 PR 목록
7. **ADR** — 의존 ADR
8. **한계** — 미구현·보류 사항
9. **NXT 영향** — ADR 0063·0091 결합 영향 (해당 트랙만)

---

## T-W1 / T-IND / T-PAT / T-FANOUT / T-PERF (v3.7.0 이월)

v3.7.0 04-feature-spec.md 전체 LOCK 이월. v3.8.0 trace 변경 0건.

---

## T-W1 — KIS Auth (v3.7.0 LOCK 이월 + v3.8.0 SystemEvent emit)

### 1. REST
- `POST /api/auth/login` · `POST /api/auth/logout` · `GET /api/auth/me` (v3.7.0 baseline 유지)

### 2. 도메인
- `UserKisCredential` (실계좌·모의 분리 — `prod` + `vps` 키 페어 LOCK)
- `KisTokenManager` + Redis cache TTL 23h

### 3. 영속
- v3.7.0 baseline V1 `user_kis_credential` (AES 암호화 — `feedback_kis_key_realonly_for_data` 정합 — 실계좌 키만 수집)

### 4. Kafka
- 없음

### 5. UI
- SettingsModal "⚙️ KIS 키 입력" — 사용자 화면 직접 입력 (`project_kis_key_management` 정합)
- v3.8.0 NotificationCenter 벨에 **KIS_TOKEN_EGW00123 self-heal alert** 노출 (PR hotfix)

### 6. PR (v3.8.0 신규)
- PR (hotfix) — KIS_TOKEN_EGW00123 self-heal (cache evict + 1회 재발급 + SystemEvent emit)

### 7. ADR
- ADR 0046 (UserKisCredential REAL/MOCK) · ADR 0057 (KIS-first raw KDoc) · ADR 0094 (운영 단계 SystemEvent fanout)

### 8. 한계
- Secret Manager 마이그레이션 미실시 (운영 본격 진입 시 별도 ADR — `project_kis_key_management` 정합)

---

## T-O 주문 실행 — v3.8.0 변경 매트릭스

### 1. REST (v3.7.0 LOCK 이월)
- 5 endpoint LOCK 유지 (`POST /api/orders` · `POST /api/orders/manual` · `POST /api/orders/{id}/cancel` · `GET /api/orders/cooldown/{code}` · `POST /api/consent/real-trading`)

### 2. 도메인 (KillSwitch race-safe 신규)

```kotlin
// consumer/.../killswitch/KillSwitchRaceSafeGuard.kt (v3.8.0 신규)
sealed interface KillSwitchEvaluation {
    object AllClear : KillSwitchEvaluation
    data class PendingHit(val existingTrigger: String) : KillSwitchEvaluation        // A
    data class GraceWindow(val activeUntil: Instant) : KillSwitchEvaluation          // B
    data class StrikeThreshold(val count: Int, val withinSeconds: Int) : KillSwitchEvaluation  // C
}

class KillSwitchRaceSafeGuard(
    private val redis: StringRedisTemplate,
) {
    fun evaluate(userId: Long, trigger: String): KillSwitchEvaluation =
        checkPending(userId)
            ?.let { return@evaluate it }                       // 리팩토링맨 lens: ?.let chain
            ?: checkGrace(userId)
                ?: checkStrikeThreshold(userId, trigger)
                ?: KillSwitchEvaluation.AllClear
    // ...
}
```

### 3. 영속 (Flyway 추가)

| Flyway | 변경 | ADR | 시점 |
|--------|------|-----|------|
| V31~V42 | v3.7.0 baseline 풀세트 LOCK 이월 | ADR 0084·0081·0082·0086·0088·0090 | — |
| **V43** | `system_event` 신설 (SystemEvent 영구 audit log) | ADR 0084 framework + v3.8.0 P1·P2 dashboard | v3.7.x |

`system_event` 컬럼 (V43) — 02-data-flow.md §5-2 정합:

```sql
id             BIGSERIAL    PRIMARY KEY,
user_id        BIGINT       NOT NULL,
event_type     VARCHAR(64)  NOT NULL,    -- KIS_RATELIMIT·KIS_TOKEN_EGW00123·KIS_REJECT·DRIFT_DETECTED·WS_DISCONNECTED·WS_RECONNECTED·KILLSWITCH_AUTO_ON 등
severity       VARCHAR(8)   NOT NULL CHECK (severity IN ('P1','P2','P3','P4')),
channel        VARCHAR(64)  NOT NULL,
payload_json   JSONB        NOT NULL,
correlation_id VARCHAR(96)  NULL,        -- WS_DISCONNECTED ↔ WS_RECONNECTED 묶음
created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
viewed_at      TIMESTAMPTZ  NULL,
resolved_at    TIMESTAMPTZ  NULL
```

### 4. Kafka (publish path LOCK 이월 + 신규 1건)

| 토픽 | v3.7.0 | v3.8.0 |
|------|--------|--------|
| `order.intent.v1` `order.requested.v1` `order.filled.v1` `order.rejected.v1` | Outbox 경유 publish | LOCK 이월 |
| `killswitch.engaged.v1` | 단일 trigger | **+ forced=true 분기 (strike threshold 시)** |
| **✨ `system.event.v1`** | 0건 | **✨ v3.8.0 신규 — SystemEventLoggerAdapter publish (직접 send — audit 권위는 system_event 테이블)** |

### 5. UI
- v3.7.0 baseline 풀세트 LOCK 이월
- **✨ NotificationCenter 벨** (frontend `useSystemEventStompSubscription` hook + 30s polling fallback) — KIS_TOKEN/KIS_REJECT/WS_DISCONNECTED 사용자 즉시 인지

### 6. PR (v3.8.0)
- PR #648 — KillSwitch race-safe guard A+B+C (T-20260521-1542)
- PR (P1) — SystemEvent emit point 5건 (KIS_RATELIMIT·KIS_TOKEN·KIS_REJECT·DRIFT_DETECTED·WS_DISCONNECTED/RECONNECTED)
- PR (P2) — SystemEvent REST API (list + viewed/resolved) + Kafka → STOMP fanout + NotificationCenter 벨
- PR (hotfix) — order FILLED stomp_event qty/executedQty 매핑 누락 fix (사용자 dogfooding 0주 toast)
- PR (hotfix) — markResolved 시 viewed_at 동반 마킹 (사용자 mental model 정합)
- PR (hotfix) — OrderListItemDto·OrderDetailDto name 필드 + instrument join

### 7. ADR
- ADR 0068·0084 (T-O baseline + v3.7.0 P0+P1) + **ADR 0043·0070 amend (KillSwitch race-safe — PR #648)** + ADR 0094 (운영 단계 SystemEvent fanout)

### 8. 한계
- KillSwitch race-safe strike threshold 3회/5분 fixed (사용자 설정화 backlog)
- SystemEvent retention 30d fixed (별도 cron 사이클 wiring 후속)
- `system.event.v1` Outbox 미경유(직접 KafkaTemplate.send) — audit 권위는 `system_event` 테이블이라 자본 path 외 LOCK

---

## ✨ T-OPS — 운영 인프라 (v3.8.0 신규 트랙 — ADR 0094 P0 OPS 6건)

운영 단계 진입 핵심 트랙. backend-core 단독 책임 (sre 영입 거절 + chart-curator 해고 evidence).

### 1. REST (v3.8.0 신규)

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/actuator/health` | docker compose health check |
| `GET` | `/actuator/info` | 빌드 + git commit 정보 |
| `GET` | `/actuator/prometheus` | Prometheus scrape (15s interval) |
| `GET` | `/actuator/metrics/{name}` | 운영자 수동 inspect |
| `GET` | `/api/system-events?severity=&channel=&status=&page=` | SystemEvent list + filter + pagination |
| `PATCH` | `/api/system-events/{id}/viewed` | viewed_at 마킹 |
| `PATCH` | `/api/system-events/{id}/resolved` | resolved + viewed_at 동반 마킹 (hotfix) |

### 2. 도메인 (운영 SLO 측정 + SystemEvent emit)

```kotlin
// domain/observability/SloMeasurementPort.kt (v3.8.0 신규)
interface SloMeasurementPort {
    fun recordSignalToOrderLatency(durationMs: Long)
    fun incrementSystemEvent(eventType: String, severity: String)
    fun recordOutboxLag(seconds: Long)
}

// domain/systemevent/SystemEvent.kt
data class SystemEvent(
    val id: Long,
    val userId: Long,
    val eventType: SystemEventType,
    val severity: SystemEventSeverity,        // P1·P2·P3·P4
    val channel: String,
    val payloadJson: String,
    val correlationId: String?,                // WS 묶음 추적
    val createdAt: Instant,
    val viewedAt: Instant?,
    val resolvedAt: Instant?,
)

enum class SystemEventSeverity { P1, P2, P3, P4 }
```

5 의제 LOCK (ADR 0094 + ADR 0095):
- A-1 — logback JSON encoder (P0-OPS-1)
- B-1 — Actuator/Prometheus + Grafana (P0-OPS-2)
- C-1 — SLO 5건 + alert routing (P0-OPS-3 ADR 0095)
- D-1 — DR/backup pg_dump + Redis snapshot (P0-OPS-4 ADR 0096)
- E-1 — Runbook 5 시나리오 R-1~R-5 (P0-OPS-5)
- F-1 — persistence monitoring + SLO 측정 source wiring (P0-OPS-6)

### 3. 영속

- Flyway V43 `system_event` (T-O §3 정합 — 모든 emit point 권위)
- 별도 Flyway 0건 (운영 인프라 layer만 보강)

### 4. Kafka

- 발행: `system.event.v1` (api/consumer `SystemEventLoggerAdapter` — direct send, audit 권위는 V43)
- 소비: `SystemEventStompNotifier` (api) — `/topic/system.user.{userId}` STOMP fanout

### 5. UI

- `frontend/src/pages/system/SystemDashboardPage.tsx` — `/system` 라우트 (P2)
- `NotificationCenter` 벨 + 드롭다운 + 1 test (P2 — Header 통합)
- API hook: `useSystemEventList` (REST + 30s polling fallback) · `useSystemEventStompSubscription` (STOMP)
- system-event REST API client + TS types

### 6. PR (v3.8.0)

- PR #650 — P0-OPS-1·2 logback JSON + Actuator/Prometheus + Grafana (T-20260521-2157-p0-ops-logback-actuator)
- PR — P0-OPS-3 SLO/SLA + alert routing ADR 0095 (architect 발행)
- PR — P0-OPS-4 DR/backup ADR 0096 + pg_dump cron (architect 발행)
- PR — P0-OPS-5 runbook 5 시나리오 (backend-core 49f1bc31)
- PR — P0-OPS-6 persistence monitoring + SLO 측정 source wiring + Grafana SLO dashboard (backend-core 60045dc1)
- PR (P1) — SystemEvent emit point 5건 (KIS_RATELIMIT 7bdcfaf2·KIS_TOKEN ad5fcc82·DRIFT_DETECTED·WS_DISCONNECTED/RECONNECTED 4f11f0c5)
- PR (P2) — SystemEvent REST API + Kafka → STOMP fanout + Frontend (NotificationCenter + /system Dashboard)

### 7. ADR

- ADR 0094 (운영 단계 transition — P0 OPS 6건 본문화) · ADR 0095 (SLO/SLA + alert routing) · ADR 0096 (DR/backup 절차) · ADR 0044 (Notification reuse) · ADR 0084 (SystemEvent framework)

### 8. 한계

- Kafka offset backup 미구현 (backlog — 자본 path 직결 0 + Outbox table이 영속 권위)
- WAL archiving (PITR) 미구현 (backlog — ADR 0096 §2-3)
- weekly backup 미구현 (backlog — ADR 0096 §2-2)
- user별 Slack webhook 분리 미구현 (backlog §49)
- Discord·Email·SMS 보조 채널 미구현 (backlog §50)
- ArchUnit 게이트(KIS REST strict gate) 후속 사이클

---

## ✨ T-QA Visual — 품질 헌터 (v3.8.0 신규 트랙 — ADR 0094 qa 격상)

운영 단계 진입의 두 번째 핵심 트랙. qa의 본질 재정의 — "QA 게이트 배제"는 보존하되 Playwright·스크린샷·시각 회귀·E2E·incident 재현 자동화.

### 1. REST
- 없음 (frontend Playwright E2E + Visual regression 자동 재현)

### 2. 도메인
- `qa/playwright/scenarios/` 5 시나리오 baseline (ADR 0094 §3-1 P0)
- `qa/visual/snapshots/` 시각 회귀 baseline

### 3. 영속
- 없음 (Playwright report HTML + 스크린샷 파일만)

### 4. Kafka
- 없음

### 5. UI
- qa Visual은 frontend 화면을 검증하는 actor 자체
- 5 시나리오 baseline: 종목 상세 (NXT) · 차트 lazy backfill · 주문 발행 · NotificationCenter · DR drill 자동 재현

### 6. PR (v3.8.0)

- PR — Visual QA 첫 셋업 — Playwright + 5 시나리오 baseline (qa 8544ba21 — v3.8.0 운영 baseline P0)
- PR #660 — qa 첫 임무 P0 hotfix — NXT 30% 휴리스틱 + stale 30분 검증 (T-20260522-1751-qa-first-mission-nxt-stale-fix)
- 보고서 — qa 첫 실전 임무 (종목상세 현재가 결함 hunt) — qa 026e16e0

### 7. ADR
- ADR 0094 (qa Visual 격상 — 운영 헌법 §10 회귀 자동화 의무) · ADR 0091 (NXT resolver — qa 첫 임무 hotfix path)

### 8. 한계
- W4 진입(T-AI·T-MCP) 시 AI 분석 결과 검증 시나리오 미발행
- 분기 DR drill 자동 재현 시나리오 1차 baseline만 (실 dump 기반 검증 후속 dispatch)

---

## ✨ T-CC 캔들 정합 — v3.8.0 변경 매트릭스 (NXT resolver + LIVE TPS fix + lazy backfill Proposed)

### 1. REST

| 메서드 | 경로 | 설명 | v3.8.0 변경 |
|--------|------|------|------------|
| `POST` | `/admin/candle-curator/backfill` | 운영자 우회 backfill (v3.7.0 baseline) | NxtMarketCodeResolver 합류 |
| `GET` | `/admin/candle-curator/gaps` | 갭 현황 조회 | — |
| `GET` | `/api/symbols/{code}/candles?before=&limit=` | Lazy backfill cursor pagination | **ADR 0093 Proposed — `limit` cap 500 → 5000 + 사용자 입력 봉수** |

### 2. 도메인

```kotlin
// consumer/.../market/nxt/NxtMarketCodeResolver.kt (v3.8.0 신규 — ADR 0091)
class NxtMarketCodeResolver(
    private val instrumentRepository: InstrumentRepository,
    private val nxtStaleAdapter: NxtStaleHeuristicAdapter,  // PR #660 qa 첫 임무
) {
    fun resolve(instrumentCode: String): MarketDivCode =
        instrumentRepository.findByCode(instrumentCode)
            ?.takeIf { it.isKospi200 || it.isKosdaq150 }    // 리팩토링맨 lens: takeIf
            ?.let {
                when {
                    nxtStaleAdapter.isStale(instrumentCode) -> MarketDivCode.J   // stale → KRX 단독
                    nxtStaleAdapter.has30pctDeviation(instrumentCode) -> MarketDivCode.J  // 30% 휴리스틱
                    else -> MarketDivCode.UN                                     // NXT 통합 우선
                }
            }
            ?: MarketDivCode.J        // allow-list 밖 → KRX 단독
}
```

### 3. 영속
- v3.7.0 V39 `candle.backfill_batch_id` LOCK 이월
- 별도 Flyway 0건

### 4. Kafka
- 없음 (admin REST + consumer worker 내부)

### 5. UI
- 운영자 admin 전용 (사용자 흐름 X)
- ✨ frontend 차트 LWC v4 lazy backfill (ADR 0093 Proposed — frontend 단독 책임 chart-curator 해고 이관)

### 6. PR (v3.8.0)
- PR — ADR 0091 NXT resolver (chart-curator 발행 evidence — 해고 직전 마지막 산출물)
- PR #659 — LiveDailyCandleScheduler 종목 간 200ms sleep 마진 (KIS_TPS_EXCEEDED LIVE root cause hotfix)
- PR #658 — H1 페이지 burst 완화 + H4 EGW00201 1초 backoff+재시도 + priority=BACKFILL 분리
- PR #660 — qa 첫 임무 NXT 30% 휴리스틱 + stale 30분 검증
- PR — consumer KIS inquire-daily-ccld block() reactor-http-nio ERROR fix (v3.8.0 cut 차단 P0 — 2635e6fe)
- PR (Proposed) — ADR 0093 차트 lazy backfill + 사용자 입력 봉수 (v3.8.x frontend 합류)

### 7. ADR
- ADR 0091 (NXT resolver — v3.8.0 권위) · ADR 0093 (Proposed — lazy backfill) · ADR 0086 (T-CC Phase 2 — v3.7.0 baseline) · ADR 0037 (Bucket4j amend — v3.7.0 baseline + LIVE TPS fix 3 PR)

### 8. 한계
- ADR 0093 차트 lazy backfill 합류 대기 (Proposed → Accepted 시 frontend 합류)
- `nxt_tradable` 컬럼 dedicated 미구현 (ADR 0091 §5 후속 과제 — backlog)
- 분봉 KIS 한도 30 거래일 LOCK (ADR 0019 §분봉 정책) — 합산 가공 외 확장 불가

### 9. NXT 영향
- ADR 0063 통합 시장코드 정책 + ADR 0091 instrument-level resolver 결합 — KOSPI200·KOSDAQ150 종목만 `UN` 우선 + 그 외 `J` fallback. 일반 KOSDAQ 종목도 NXT eligible 합류 시 후속 ADR로 allow-list 확장.

---

## ✨ T-SIG·T-DSL strategy-engine 통합 (v3.8.0 신규 — ADR 0092)

### 1. REST (v3.7.0 LOCK 이월)

| 메서드 | 경로 | 설명 | v3.8.0 변경 |
|--------|------|------|------------|
| `POST` | `/api/strategy/{id}/evaluate` | 전략 단일 평가 | ✨ JvmStrategyEvaluatorAdapter `@Primary` 실평가 (HOLD stub 차단) |
| `POST` | `/api/screener/run` | 스크리너 inline 실행 | ✨ JvmStrategyEvaluatorAdapter 실평가 |
| `POST` | `/api/backtest/run` | 백테스트 발행 | ✨ BacktestEvaluatorBridge → JvmStrategyEvaluatorAdapter |
| `POST·PUT` | `/api/strategy` | 전략 CRUD | ✨ KisYamlDslParser.parse() + validateStatic() — 실 AST 저장 (빈 stub 차단) |

### 2. 도메인 (strategy-engine 모듈 신설)

```
strategy-engine/                            (Pure Kotlin library jar — Boot main 0)
├── kr.spdaewoon.magicjar.strategy.engine.parser
│     └── KisYamlDslParser              ← v3.7.x consumer 이관
├── kr.spdaewoon.magicjar.strategy.engine.evaluator
│     ├── JvmStrategyEvaluator          ← v3.7.x consumer 이관
│     └── HoldStrategyEvaluatorStub     ← test fallback만 (실 운영 path 0)
└── kr.spdaewoon.magicjar.strategy.engine.callback
      └── EvaluatorTimeoutCallback

domain/
└── kr.spdaewoon.magicjar.domain.strategy.port
      └── StrategyEvaluatorPort          ← api·consumer 공유 시그니처
```

### 3. 영속
- v3.7.0 baseline LOCK 이월 (Flyway 변경 0건)

### 4. Kafka
- v3.7.0 baseline LOCK 이월 — `signal.generated.v1` 발행자가 `JvmStrategyEvaluator` (consumer) 본체로 통일

### 5. UI
- `frontend/src/pages/Strategy/StrategyBuilderPage.tsx` (v3.7.0 baseline) — DSL parse 실패 시 사용자 피드백 강화 (frontend 자율 영역)

### 6. PR (v3.8.0)
- PR — ADR 0092 strategy-engine 모듈 신설 (architect 발행)
- PR — consumer.application.strategy 이관 + api·consumer 의존 등록
- PR — JvmStrategyEvaluatorAdapter `@Primary` wiring (api `StrategyService` + BacktestEvaluatorBridge + ScreenerEngineImpl)

### 7. ADR
- ADR 0092 (strategy-engine 모듈 + DSL evaluator api·consumer 공유) · ADR 0004 (모듈 경계 — 7→8 확장) · ADR 0083 (indicator-impl 패턴 baseline) · ADR 0088 (persistence-shared 패턴 baseline)

### 8. 한계
- `LiveCandleMerger` 공용 정책 미구현 (ADR 0092 §결과 후속 과제 — backlog)
- `CandleEvaluationSource` 포트 미신설 (당일 진행 중 캔들 합류는 consumer `LiveCandleMergingSource` 단독 — api·screener·backtest는 DB candle repository만)

---

## T-RECON · T-NOTIFY · T-SC · T-J · T-PERF · T-NEWS · T-ACCT · T-MAN (v3.7.0 LOCK 이월)

v3.7.0 04-feature-spec.md 풀세트 LOCK 이월. v3.8.0 변경 매트릭스:

| 트랙 | v3.8.0 변경 |
|------|------------|
| T-RECON | + DRIFT_DETECTED SystemEvent emit (BalanceReconcileService P1) — PR ID 373ca908 |
| T-NOTIFY | + Alert routing P1·P2·P3 매트릭스 (ADR 0095) — Slack webhook reuse |
| T-SC 스크리너 | + JvmStrategyEvaluatorAdapter 실평가 (HOLD stub 차단 — ADR 0092 정합) |
| T-J 매매일지 | v3.7.0 baseline LOCK 이월 (변경 0건) |
| T-PERF 성과 | v3.7.0 baseline LOCK 이월 |
| T-NEWS | v3.7.0 baseline LOCK 이월 |
| T-ACCT 잔액 | v3.7.0 baseline LOCK 이월 + 한 hotfix: PositionsPage data source 정합 (PR #643 — frontend account/positions 사용) |
| T-MAN 수동 주문 | v3.7.0 baseline LOCK 이월 (cooldown guard·idempotency·source='MANUAL') |

---

## T-AI 옵션 분석 보조 (v3.7.0 LOCK 이월 — W4 진입 대기)

- ADR 0094 §1-2 — ai-engineer "유지 (W4 진입 시 가동)" 결재
- v3.8.0 baseline 시점 가동 0건
- 사용자 결재 + 운영 단계 dwell evidence 확보 후 W4 dispatch

---

## ✨ ADR 0091 NXT 통합 시장코드 종목 단위 resolver § (v3.8.0 권위)

T-CC 트랙 §9 NXT 영향 참조. 본 §는 ADR 결정 본문 요약 + 알고리즘 매트릭스.

### 결정 매트릭스 (ADR 0091 §3)

1. `market == KOSPI`라는 이유만으로 `UN`을 선택하지 않는다.
2. 1차 MVP는 `is_kospi200`·`is_kosdaq150`을 보수적 NXT allow-list로 사용.
3. allow-list 종목은 `UN` 우선 시도 → 응답 0건이면 `J` 재시도.
4. allow-list 밖 종목, `UNKNOWN`, 기타 market은 `J` 기본값.
5. 후속 — NXT 공식 거래 가능 종목 목록 수집 → `nxt_tradable` 또는 status-history 승격 (backlog).

### qa 첫 임무 hotfix 매트릭스 (PR #660)

- NXT 30% 휴리스틱 — UN 응답 volume이 KRX 대비 ±30% 편차 시 stale 의심
- stale 30분 검증 — 마지막 backfill 시점이 30분 이상 경과 시 forced refresh
- Redis 키 `nxt:stale:check:{code}` (TTL 1800s — 02-data-flow.md §6-2 정합)

### KIS-first raw 출처 (ADR 0091 §결정)
- KIS daily item chart raw: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py`
- KIS time daily chart raw: `https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py`
- NXT market overview: `https://www.nextrade.co.kr/en/marketOverview/content.do`

---

## ✨ ADR 0092 strategy-engine shared DSL evaluator § (v3.8.0 권위)

T-SIG·T-DSL §에서 본문 요약. 본 §는 ADR 의제별 LOCK 매트릭스.

### HOLD stub P0 결함 차단 매트릭스

| 영역 | v3.7.0 | v3.8.0 |
|------|--------|--------|
| api 전략 등록 | 빈 `IndicatorRef("stub")` AST 저장 risk | **KisYamlDslParser.parse() + validateStatic() → 실 AST** |
| api 전략 평가 | `HoldStrategyEvaluatorStub` 사용 가능 | **`JvmStrategyEvaluatorAdapter @Primary` 실평가** |
| api 백테스트 | 실 매매와 다른 path | **BacktestEvaluatorBridge → JvmStrategyEvaluator** |
| api 스크리너 | 실 매매와 다른 path | **ScreenerEngineImpl → JvmStrategyEvaluator** |
| consumer 실시간 시그널 | `JvmStrategyEvaluator` 본체 | **동일 본체 — 5 path 통일** |

### Timeout auto-disable 콜백 (ADR 0092 §결과)

`api/StrategyService` (@Primary 구현체)가 `EvaluatorTimeoutCallback` 등록 → strategy-engine evaluator 내부에서 timeout 발생 시 DB `strategy.auto_disabled_at = NOW()` UPDATE + STOMP 알림.

---

## ✨ ADR 0093 차트 캔들 history limit + Lazy Backfill (Proposed — v3.8.x 합류) §

### timeframe별 default + max 매트릭스 (ADR 0093 §3-1 LOCK)

| Interval | Default (초기) | User 입력 옵션 | Max cap (lazy 누적) | KIS cover (max) |
|----------|---------------|----------------|---------------------|----------------|
| `1m` | 600 | 600 / 1200 / 2400 / 직접입력 | 4800 | 약 13 거래일 (KIS 한도 30 거래일 LOCK) |
| `5m`·`15m`·`30m`·`1h`·`90m` | 480 | 480 / 960 / 1920 / 직접입력 | 3840 | 5m=약 40 거래일 / 1h=약 320 거래일 |
| `D` (일봉) | 300 | 300 / 600 / 1200 / 3000 / 직접입력 | **6000 (24년치)** | 약 24년 |
| `W`·`M`·`Y` | 300 | 300 / 600 / 직접입력 | 3000 | W=약 60년 / M=약 250년 / Y=약 3000년 |

### Lazy backfill 흐름 (frontend 단독 — chart-curator 해고 후 책임 이관)

```
LightweightChart subscribeVisibleLogicalRangeChange
    ↓ range.from 단조감소 + 1초 cooldown 가드
GET /api/symbols/{code}/candles?before={oldest_ts}&limit={N}
    ↓ DB candle repository (limit cap 500 → 5000 — ADR 0093 §3-2)
LWC setData prepend
```

### 한계
- v3.8.0 baseline 시점 Proposed (Accepted 아님) — v3.8.x 후속 사이클 합류 대기
- chart-curator 해고로 frontend 단독 책임 (ADR 0094 §2-2 LWC v4 이관)

---

## ✨ ADR 0094 운영 단계 transition § (v3.8.0 권위)

본 ADR은 v3.8.0 전체 baseline의 권위 source. T-OPS·T-QA Visual 트랙 자체가 ADR 0094 §3 P0 OPS 6건의 본문화.

### 8 → 7 hierarchy reshuffle 매트릭스 (ADR 0094 §1-2)

| 에이전트 | 결정 | 사유 |
|---------|------|------|
| architect · backend-core · market-data · strategy-engine · frontend · ai-engineer (W4) | 유지 | 안정 dwell + 운영 단계 fitness |
| **backend-core** | **유지 + P0 OPS 6건 인수** | sre 영입 거절 → 운영 인프라 단일 owner |
| **market-data** | **유지 + 캔들 SLA 인수** | chart-curator 캔들 적재 책임 이관 |
| **frontend** | **유지 + LWC v4 인수** | chart-curator TradingView LWC v4 컴포넌트 책임 이관 |
| **qa** ★ | **격상 — Visual "품질 헌터"** | Playwright + 스크린샷 + 시각 회귀 + E2E + DR drill 자동 재현 |
| **chart-curator** | **해고** | 캔들 SLA → market-data / LWC → frontend / 영역 분산 정합 |
| **sre** (영입 제안) | **영입 거절** | 기존 actor 강화 우선 + 운영 부담 ↑ 회피 |

### P0 OPS 6건 본문화 (ADR 0094 §3-2)

| Task | 책임 | 본문화 ADR |
|------|------|-----------|
| P0-OPS-1 | backend-core | logback JSON encoder (ADR 0094 §3-2) |
| P0-OPS-2 | backend-core | Actuator/Prometheus + Grafana (ADR 0094 §3-2) |
| P0-OPS-3 | backend-core spec | SLO/SLA + alert routing — **ADR 0095 별도 발행** |
| P0-OPS-4 | backend-core spec | DR/backup — **ADR 0096 별도 발행** |
| P0-OPS-5 | backend-core | Runbook 5 시나리오 R-1~R-5 |
| P0-OPS-6 | backend-core | persistence monitoring + SLO 측정 source wiring |

### 운영 헌법 §10 신설 (ADR 0094 §9)

- §10-1: SLO 6건 baseline + alert routing (ADR 0095)
- §10-2: 운영 dispatch 1~3 PR/사이클 (개발 단계 5~15 PR 대비 감소)
- §10-3: qa Visual 회귀 자동화 의무 (frontend 핵심 화면 변경 시)
- §10-4: 분기 DR drill 사용자 결재 의무 + qa Visual E2E 자동 재현
- §10-5: W3·W4·W5 보류 권한 (사용자 LOCK)
- §10-6: 백로그 운영 무관 후순위 trim 의무

---

## ✨ ADR 0095 SLO/SLA + alert routing § (v3.8.0 권위)

T-OPS §에서 본문 요약. 본 §는 SLO 5건 권위 매트릭스.

### SLO 5건 LOCK 매트릭스 (ADR 0095 §2-1)

| # | 항목 | SLO (목표) | SLA (minimum) | 측정 source | severity |
|---|------|-----------|---------------|------------|----------|
| **S-1** | 시그널 → 주문 지연 | P95 < 5s | < 10s | Micrometer `magicjar.signal.to.order.latency` (consumer wiring) | warning ≥5s/5m / critical ≥10s/2m |
| **S-2** | KIS WS 연결률 (09:00~15:30 KST) | > 99% | > 95% | SystemEvent `WS_DISCONNECTED` 빈도 (PR P1 emit 4·5) | warning ≥1/5m / critical ≥3/5m |
| **S-3** | account API 지연 | p95 < 500ms | < 2s | http.server.requests timer (`/api/account/*`) | warning ≥500ms/5m / critical ≥2s/2m |
| **S-4** | KIS rate limit 초과 | < 1건/5분 | < 5건/5분 | SystemEvent `KIS_RATELIMIT` 빈도 (PR #640 emit) | warning ≥1/5m / critical ≥5/5m |
| **S-5** | KillSwitch 자동 ON | < 1건/일 | < 3건/일 | `killswitch.engaged.v1` Kafka 빈도 (SystemEvent KILLSWITCH_AUTO_ON 보조) | warning ≥1/일 / critical ≥3/일 |

### Alert routing 매트릭스 (ADR 0095 §3)

| severity | channel | dedup window | actor |
|----------|---------|--------------|-------|
| P1 critical | Slack #magicjar-incident | 0 (즉시) | on-call backend-core |
| P2 warning | Slack #magicjar-ops | 5분 | backend-core (영업일 day shift) |
| P3 info | SystemEvent emit only | — | audit |
| P4 debug | discard | — | dev profile 한정 |

---

## ✨ ADR 0096 DR/backup 절차 § (v3.8.0 권위)

T-OPS §에서 본문 요약. 본 §는 RPO/RTO + backup 매트릭스.

### PostgreSQL backup (ADR 0096 §2)

| 항목 | 본문 |
|------|------|
| 주기 | 매일 03:00 KST (macOS crontab 또는 launchd plist) |
| 명령 | `docker exec magicjar-postgres pg_dump -U magicjar -Fc magicjar` |
| 출력 | `_workspace/backups/postgres/daily/YYYY-MM-DD.dump` (pg_dump custom format) |
| retention | 7일 (`find -mtime +7 -delete`) |

### Redis snapshot (ADR 0096 §3)

| 항목 | 본문 |
|------|------|
| 주기 | 매일 03:30 KST |
| 명령 | `docker exec magicjar-redis redis-cli BGSAVE` + `docker cp magicjar-redis:/data/dump.rdb _workspace/backups/redis/daily/YYYY-MM-DD.rdb` |
| retention | 3일 |

### RPO/RTO (ADR 0096 §4)

| 항목 | 정의 | 비고 |
|------|------|------|
| RPO | **24h** | 매일 backup |
| RTO | **4h** | 사용자 결재 + pg_restore + Redis restore + 재기동 |

### 분기 DR drill (ADR 0096 §6 + 운영 헌법 §10-4)

- 분기 1회 사용자 결재 → backend-core staging restore → qa Visual E2E 자동 재현
- drill 실패 시 runbook R-2 정합 행동

---

## ADR 의존 요약 (v3.8.0 갱신)

| ADR | 영향 트랙 |
|-----|---------|
| ADR 0004 | 모든 트랙 (모듈 경계 **8건**으로 확장) |
| ADR 0007 | 모든 Kafka 트랙 (Outbox publish path LOCK) |
| ADR 0010 | 모든 user-isolated 트랙 (userId number 통일 정합) |
| ADR 0023 | T-DSL·T-BT·T-SIG·T-RISK·T-O·T-MAN (룰 온리 + AI 호출 0) |
| ADR 0024·0043·0070 | **T-RISK KillSwitch — A+B+C race-safe guard amend (PR #648)** |
| ADR 0037 | **모든 KIS REST 어댑터 (Bucket4j-Redis amend + LIVE TPS fix 3 PR)** |
| ADR 0044 | T-NOTIFY (Alert routing P1·P2·P3 — ADR 0095 정합) |
| ADR 0046 | T-W1 KIS Auth (REAL/MOCK fetch) |
| ADR 0051 | 모든 JPA Repository (body 0 의무 — 8 모듈) |
| ADR 0054·0083 | T-IND·T-PAT (pattern-engine·indicator-impl baseline) |
| ADR 0057 | KIS REST 호출 모든 트랙 (raw KDoc inline) |
| ADR 0068·0084 | **T-O 자본 path — v3.7.0 P0+P1 + v3.8.0 KillSwitch race-safe + SystemEvent emit 5건** |
| ADR 0081 | T-ACCT (v3.7.0 baseline) |
| ADR 0082 | T-MAN (v3.7.0 baseline) |
| ADR 0086 | T-CC Phase 2 (v3.7.0 baseline) |
| ADR 0087 | Sub-agent 페르소나 매트릭스 (보수·진보) — ADR 0094 §2-2 reshuffle 후 backend-core·qa 페르소나 재배치 |
| ADR 0088 | persistence-shared 모듈 (v3.7.0 LOCK) |
| ADR 0089 | userId number 통일 (v3.7.0 LOCK) |
| ADR 0090 | instrument_status_history Phase 1·2 (v3.7.0 LOCK) |
| ADR 0091 | **NXT 통합 시장코드 종목 단위 resolver** (v3.8.0 신규) |
| ADR 0092 | **strategy-engine 모듈** (v3.8.0 신규 — 8 모듈 확장) |
| ADR 0093 | **차트 캔들 lazy backfill** (Proposed — v3.8.x 합류) |
| ADR 0094 | **운영 단계 transition** (v3.8.0 권위 — 8→7 hierarchy + qa Visual 격상 + chart-curator 해고 + P0 OPS 6건) |
| ADR 0095 | **SLO/SLA + alert routing** (v3.8.0 신규 — S-1~S-5 + P1·P2·P3) |
| ADR 0096 | **DR/backup 절차** (v3.8.0 신규 — pg_dump + Redis + RPO 24h/RTO 4h) |

---

## 💡 v3.8.0 리팩토링맨 lens 진보 평가 (트랙 매트릭스)

| 트랙 | v3.7.0 상태 | v3.8.0 상태 | 진보도 |
|------|-----------|-----------|-------|
| T-O 주문 실행 | 자본 path P0+P1 (Outbox + event_id + PENDING_RECONCILE) | + KillSwitch race-safe + SystemEvent emit 5건 + EGW00201 1초 backoff | ★★★★★ |
| T-RISK KillSwitch | 단일 trigger | A+B+C race-safe guard (pending+grace+strike) | ★★★★★ |
| T-SIG·T-DSL | consumer-only (api HOLD stub risk) | strategy-engine 모듈 (api·consumer 공유 — HOLD stub P0 영구 차단) | ★★★★★ |
| **T-OPS 운영 인프라** ★ | 0건 | **★ logback JSON + Actuator/Prometheus + Grafana + SLO 5건 + DR + Runbook + persistence monitoring** | ★★★★★ |
| **T-QA Visual** ★ | qa 1건 dispatch | **★ Playwright + 5 시나리오 + 첫 실전 결함 hunt 성공** | ★★★★★ |
| T-CC 캔들 정합 | KOSPI=UN/KOSDAQ=J market-level | NXT instrument-level allow-list + fallback + 30% 휴리스틱 + stale 30분 + LIVE TPS fix 3 PR | ★★★★ |
| T-LWC 차트 lazy backfill | 600봉 cap | ADR 0093 Proposed (v3.8.x 합류) | ★★★ |
| T-W1 KIS Auth | baseline | + KIS_TOKEN_EGW00123 self-heal + SystemEvent emit | ★★★ |
| 인프라 (모듈) | 7 모듈 | **8 모듈 (+ strategy-engine)** | ★★★★ |
| 자본 path | P0+P1 baseline | + KillSwitch race-safe + LIVE TPS fix + SystemEvent emit | ★★★★ |

✨ **W3 마감 + 운영 단계 진입 종합**:
- v3.7.0이 자본 path 안정성(ADR 0084 P0+P1) 마감이었다면 v3.8.0은 **운영 단계 1차 baseline** — 6 운영 영역 풀세트 + 5 자본 path 보강
- 트랙 추가 0건 + 운영 단계 fitness 9건 충족 — "기능 완성 + 트랙 진입" → "안정 운영 + 점진 개선" mindset 전환 완료
- chart-curator 해고 + qa Visual 격상 + sre 영입 거절 — 7 hierarchy 정착 (ADR 0094)
- W4 진입(T-AI·T-MCP)은 운영 단계 안정 dwell evidence 확보 후 사용자 결재 의존 (운영 헌법 §10-5 LOCK)

🔄 **backlog 매트릭스 (v3.8.0 → v3.8.x patch 후보)**:
- ADR 0093 차트 lazy backfill 합류 (Proposed → Accepted — frontend 단독 책임)
- weekly backup + WAL archiving (ADR 0096 §2-2·§2-3)
- ArchUnit 게이트(KIS REST strict gate)
- AccountReconcile cron + MarketSessionFsm 결합
- drift 임계치 사용자 설정화 + KillSwitch strike threshold 사용자 설정화
- user별 Slack webhook 분리 + Discord/Email/SMS 보조 채널
- T-J 차트 스냅샷 자동 영속
- T-PERF Sharpe·Sortino·CAGR 지표 확장
- 일별 잔고 history 영속 (T-ACCT)
- cooldown 60s 사용자 설정화 (T-MAN)
- W4 진입 게이트(T-AI·T-MCP) — 사용자 결재 + 운영 단계 dwell evidence

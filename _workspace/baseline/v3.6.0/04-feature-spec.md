# v3.6.0 기능 명세 (트랙별 표준 9 항목)

- baseline: v3.6.0
- 작성일: 2026-05-13
- 작성자: backend-core (bot+backend-core@magicjar.local)
- SoT: `docs/architecture/master-architecture.md` + ADR INDEX (`_workspace/arch/adr/README.md`)
- 기준: v3.5.0 04-feature-spec.md 전체 이월 + v3.6.0 T-O 트랙 추가 (ADR 0068 A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택)

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
9. **NXT 영향** — ADR 0063 결합 영향 (해당 트랙만)

---

## T-W1 — Watchlist (관심종목 CRUD)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/watchlists` | 목록 (priority ASC + created_at DESC) |
| `POST` | `/api/watchlists` | 등록 — UQ(user_id, code) 위반 시 409 |
| `DELETE` | `/api/watchlists/{code}` | 삭제 — 미등록 시 404 |

OnboardingGuardFilter 통과 의무 (셋업 미완 시 401 ONBOARDING_REQUIRED).

### 2. 도메인

- `domain/watchlist/WatchlistEntry.kt` — `data class WatchlistEntry(code, userId, priority, createdAt)`
- `domain/watchlist/port/WatchlistRepository.kt` — 포트 인터페이스 (JPA 의존 0)

### 3. 영속

- `api/persistence/WatchlistJpaEntity.kt` — JPA 엔티티
- `api/persistence/WatchlistJpaRepository.kt` — Spring Data JPA (abstract fun body 0 — ADR 0051)
- Flyway: `V*__create_watchlist_entry.sql` — `watchlist_entry(id, user_id, code, priority, created_at)` UQ(user_id, code)

### 4. Kafka

| 토픽 | action | 발행자 | 소비자 |
|------|--------|-------|-------|
| `watchlist.changed.v1` | ADDED / REMOVED | `WatchlistService` (api) | `WatchlistChangedKafkaListener` (consumer) |

### 5. UI

- `frontend/src/pages/Watchlist/WatchlistPage.tsx` — 관심종목 목록 화면
- `frontend/src/pages/Watchlist/WatchlistAddModal.tsx` — 종목 등록 모달

### 6. PR

- PR #202 (T-W1 R1 도메인+REST scaffold)
- PR #207 (T-W1 R2 JPA + Kafka 발행 통합)

### 7. ADR

- ADR 0040 — Watchlist-first UI 흐름
- ADR 0028 — Watchlist 기반 Ingestion Worker
- ADR 0007 — Kafka 이벤트 backbone

### 8. 한계

- priority 수정 API 미구현 (backlog §20)
- 다중 사용자 multi-tenant 미지원 (단일 user_id — ADR 0010 §2 1차 MVP)
- watchlist 100종목 초과 시 UI 페이지네이션 미구현

---

## T-S1 — Symbol Detail (종목 상세)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/symbols/{code}` | 현재가·PER·PBR·시총·업종 |
| `GET` | `/api/symbols/{code}/candles` | 캔들 시계열 (DB + Redis current 병합) |
| `POST` | `/api/symbols/{code}/orderbook/subscribe` | 호가 구독 시작 |
| `POST` | `/api/symbols/{code}/orderbook/unsubscribe` | 호가 구독 해제 |

### 2. 도메인

- `domain/market/candle/Candle.kt` — `data class Candle(code, interval, ts, openRaw, highRaw, lowRaw, closeRaw, openAdj, highAdj, lowAdj, closeAdj, volume, source)`
- `domain/market/candle/CandleInterval.kt` — enum 11종
- `domain/market/port/CandleRepository.kt` — `saveAll`·`countByCodeAndInterval`·`findByCodeAndInterval`
- `domain/market/port/QuoteCachePort.kt` — `getLatestPrice`·`putLatestPrice`
- `domain/market/port/KisRestPort.kt` — `fetchCandles`·`fetchPrice`·`fetchPriceMetrics`

### 3. 영속

- `api/persistence/CandleJpaEntity.kt` + `CandleJpaRepository.kt`
- `consumer/persistence/ConsumerCandleJpaEntity.kt` + `ConsumerCandleJpaRepository.kt`
- Flyway: V1 `candle(id, code, interval, ts, ...)` UQ(code, interval, ts)

### 4. Kafka

| 토픽 | 방향 | 설명 |
|------|------|------|
| `symbol.detail.command.v1` | api→consumer | ENTER/EXIT 호가 구독 명령 |
| `market.tick.v1` | consumer→api | 실시간 체결가 |
| `market.candle.minute.closed.v1` | consumer→api | 1분봉 closing |
| `market.orderbook.v1` | consumer→api | 호가 스냅샷 |

STOMP 토픽: `/topic/market.tick.{code}` · `/topic/market.candle.minute.{code}` · `/topic/market.orderbook.{code}`

### 5. UI

- `frontend/src/pages/Symbol/SymbolPage.tsx` — 종목 상세 페이지
- `frontend/src/pages/Symbol/SymbolPriceCard.tsx` — 현재가·등락 (STOMP 단일 source)
- `frontend/src/pages/Symbol/SymbolChart.tsx` + `LightweightChart.tsx` — LWC v4 차트
- `frontend/src/pages/Symbol/IntervalToggle.tsx` — 10종 인터벌

### 6. PR

- PR #210 (T-S1 R1) · PR #215 (T-S1 R2) · PR #289 (tick sync fix) · PR #291 (STOMP hotfix)

### 7. ADR

- ADR 0029·0048·0057·0062·0063·0064

### 8. 한계

- 호가 UI 분리 팝업 미구현 · 차트 그리기 도구 미구현 · interval 2m 비활성

### 9. NXT 영향 (ADR 0063)

- `isIntradayInterval()` NXT 08:00~20:00 확장 · AGGREGATED_INTERVALS 5종 활성화 · LWC series.update()

---

## T-IND — Indicator (기술적 지표 80종)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/indicators` | 80지표 계산 |

### 2. 도메인

- `domain/indicator/IndicatorEngine.kt` · `IndicatorCatalog.kt` · `IndicatorSpec.kt` · `IndicatorResult.kt` · `IndicatorPoint.kt`

### 3. 영속

- DB 직접 영속 없음 — Redis 캐시 (`indicator:cache:{code}:{interval}:{name}:{paramsHash}`)

### 4. Kafka

없음.

### 5. UI

- `frontend/src/pages/Symbol/IndicatorPanel.tsx`

### 6. PR

- PR #230·#240

### 7. ADR

- ADR 0021·0019

### 8. 한계

- 실시간 tick 기반 지표 미구현 · adjusted=true 경로 fallback

---

## T-DSL — Strategy DSL

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/strategy` | 전략 등록 |
| `GET` | `/api/strategy` | 전략 목록 |
| `GET` | `/api/strategy/{id}` | 단건 조회 |
| `DELETE` | `/api/strategy/{id}` | 삭제 |
| `POST` | `/api/strategy/validate` | DSL 검증 |
| `PUT` | `/api/strategy/{id}/enable` | 재활성화 |
| `POST` | `/api/strategy/{id}/evaluate` | 평가 (stub) |
| `POST` | `/api/ai/dsl/translate` | 자연어 → DSL |

> v3.4.0 이후: T-SIG `StrategiesController` `/api/strategies`가 strategy 관리의 주 경로. `/api/strategy`는 T-DSL R1·R2 레거시 경로 보존.

### 2. 도메인

- `domain/strategy/Strategy.kt` · `DslStrategyDefinition.kt` · `StrategyComplexity.kt` · `Signal.kt` enum · `port/StrategyRepositoryPort.kt`
- `domain/backtest/*`

### 3. 영속

- `api/persistence/StrategyJpaEntity.kt` + `StrategyJpaRepository.kt`
- Flyway V9 + V14(v3.4.0 status·codes·intervals 확장)

### 4. Kafka

- `JvmStrategyEvaluator` — T-SIG에서 실시간 평가 sandbox (ADR 0047)

### 5. UI

- `frontend/src/pages/Strategy/StrategyBuilderPage.tsx` · `DslEditor.tsx`

### 6. PR

- PR #220·#225

### 7. ADR

- ADR 0022·0023·0047·0055

### 8. 한계

- `/api/strategy/{id}/evaluate` stub · 전략 import UI 미구현 · 공유·클론 미구현

---

## T-PAT — Pattern Recognition (57종)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/patterns` | 57종 패턴 인식 |

### 2. 도메인

- `domain/pattern/port/PatternRecognizer.kt` · `PatternMatch.kt` · `pattern-engine/JvmPatternRecognizer.kt` (Pure Kotlin — ADR 0054)

### 3. 영속

- DB 직접 영속 없음 — Redis 캐시

### 4. Kafka

없음.

### 5. UI

- `frontend/src/pages/Symbol/PatternOverlay.tsx`

### 6. PR

- PR #241·#261

### 7. ADR

- ADR 0054·0019

### 8. 한계

- 57종 중 R2 stub 47건 → direction=NEUTRAL · 실시간 패턴 감지 미구현 · 마커 클릭 팝업 미구현

---

## T-BT — Backtest Engine (ADR 0056 + ADR 0061 — v3.3.1 마감)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/backtest/run` | stateless (ADR 0056 §5-1 보존) |
| `POST` | `/api/backtests` | 실행 + 영속 |
| `GET` | `/api/backtests` | 사용자 목록 (페이징) |
| `GET` | `/api/backtests/{id}` | 단건 메타 + KPI |
| `GET` | `/api/backtests/{id}/result` | 거래·포지션 시계열 |
| `DELETE` | `/api/backtests/{id}` | 삭제 (CASCADE) |

### 2. 도메인

- `domain/backtest/BacktestRequest.kt` · `BacktestResult.kt` (sealed Success·Aborted) · `BacktestTrade.kt` · `BacktestPosition.kt` · `BacktestPerformance.kt` · `LookaheadGuard.kt`
- port: `BacktestEngine.kt` · `BacktestCommissionModel.kt` · `BacktestSlippageModel.kt` · `BacktestTaxModel.kt` · `StrategyEvaluatorPort.kt`

### 3. 영속

- Flyway V12(backtest_run) · V13(backtest_trade·backtest_position)
- `BacktestPersister.kt` @Transactional · `BacktestQueryService.kt`

### 4. Kafka

없음.

### 5. UI

- `BacktestPage.tsx` · `BacktestForm.tsx` · `BacktestList.tsx` · `BacktestDetail.tsx` · `BacktestKpiPanel.tsx` · `EquityCurveChart.tsx` (LWC v4) · `TradeTable.tsx`

### 6. PR

- PR #251·#260·#331·#332

### 7. ADR

- ADR 0056·0061·0047·0023

### 8. 한계

- 다중 전략 동시 백테스트 미구현 · 백테스트 vs actual 시그널 비교 뷰 미구현 (T-J) · 통과 → 활성화 게이트 미구현

---

## T-FANOUT — 실시간 boundary fanout (v3.3.0 — ADR 0064)

### 1. REST

별도 없음 (admin trigger: `POST /admin/aggregate/{code}` port 8082).

### 2. 도메인

- `domain/market/candle/BoundaryDetector.kt` — Pure Kotlin object (ADR 0064 §4-1)
  - KIS-first raw 출처: H0STCNT0 `ccnl_krx()` `[1]` STCK_CNTG_HOUR

### 3. 영속

- `candle` 테이블 동일 스키마 (ON CONFLICT DO NOTHING) · `RedisCandleCache.kt`

### 4. Kafka

5종 `market.candle.{interval}.closed.v1` — 소비자: `CandleClosedStompListener`(api) + `BoundaryClosingKafkaListener`(consumer)

### 5. UI

- `useSymbolCandles.ts` · `LightweightChart.tsx` series.update() · `SymbolChart.tsx`

### 6. PR

- PR #318·#319·#320·#321

### 7. ADR

- ADR 0064·0029·0062·0063·0023·0019·0051

### 8. 한계

- tick 0건 종목 boundary 누락 risk · in-process map 재기동 시 손실 (ON CONFLICT DO NOTHING 멱등) · 합산 결과 staleness

### 9. NXT 영향 (ADR 0063)

- boundary 정렬: 08:00~20:00 KST · DailyCloseFinalizer 20:00 flush

---

## T-SIG — 실시간 시그널 엔진 (v3.4.0 — ADR 0066)

> **AI 호출 0 의무** — ADR 0023 §2-4 + ADR 0066 §1 LOCK

### 1. REST

#### Strategy CRUD (6 endpoint)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `POST` | `/api/strategies` | 룰 등록 + ADR 0047 §2-1 static 검증 |
| `GET` | `/api/strategies` | 목록 (페이징) |
| `GET` | `/api/strategies/{id}` | 단건 + metric summary |
| `PUT` | `/api/strategies/{id}` | 수정 |
| `DELETE` | `/api/strategies/{id}` | soft delete |
| `PUT` | `/api/strategies/{id}/toggle` | status 토글 |

#### Signal 조회 (2 endpoint)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `GET` | `/api/signals` | 시계열 페이징 |
| `GET` | `/api/signals/{id}` | 단건 |

### 2. 도메인

```kotlin
data class Signal(
    val id: Long, val strategyId: Long, val userId: String,
    val code: String, val interval: CandleInterval, val ts: Instant,
    val side: SignalSide, val strength: Int, val reason: String,
    val priceAtSignal: BigDecimal, val contextJson: String, val createdAt: Instant,
)
enum class SignalSide { BUY, SELL, HOLD }
enum class StrategyStatus { ACTIVE, PAUSED, DISABLED, DELETED }
```

포트: `SignalRepository.kt` · `StrategyLookupPort.kt` · `SignalDispatcher.kt` · `SignalGenerator.kt` · `SignalContext.kt`

### 3. 영속

Flyway V14(strategy 확장) · V15(signal 신규 — UQ strategy_id+code+interval+ts)

JPA: `SignalJpaEntity/Repository/Adapter` · `StrategyJpaEntity` 확장 · `StrategyLookupJpaAdapter` · `RedisStrategyCache` (TTL 5분)

### 4. Kafka

| 토픽 | 방향 | 발행자 | 소비자 |
|------|------|-------|-------|
| `market.candle.{interval}.closed.v1` (5종) | consumer→consumer | BoundaryAggregator | BoundaryClosingKafkaListener |
| `signal.generated.v1` | consumer→api+consumer | SignalKafkaProducer | StompSignalDispatcher(api) + SignalJpaAdapter(api) + **SignalGeneratedKafkaListener(consumer, v3.5.0 추가)** |

STOMP: `/topic/signal.user.{userId}` — userId 단위 격리

### 5. UI

- `StrategyListPage.tsx` · `StrategyCard.tsx` · `SignalListPanel.tsx` · `useStompSignal.ts` · `useStrategies.ts` · `useSignals.ts`

### 6. PR

- PR #335·#336·#337·#338

### 7. ADR

- ADR 0066·0023·0047·0064·0022·0061·0021·0007·0029·0010·0051·0045

### 8. 한계

- HOLD 시그널 영속 0건 · signal row ≥ 5M 시 monthly partition 미구현 · D-2 다채널 알림 미구현 (T-NOTIFY)

### 9. NXT 영향 (ADR 0063)

- 시그널 평가 boundary 08:00~20:00 KST · DailyCloseFinalizer 20:00 → strategy cache invalidateAll

---

## T-RISK — 리스크 관리 게이트 (v3.5.0 신규 — ADR 0067)

> **AI 호출 0 의무** — 본 트랙 모든 컴포넌트 LLM provider import 0건 (ADR 0023 §2-4 + ADR 0067 §8 inline 주석 정책 LOCK).

### 1. REST

#### RiskConfig (3 endpoint)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `GET` | `/api/risk-config` | 사용자 risk 설정 조회 (부재 시 default seed 반환 — H-1) |
| `PUT` | `/api/risk-config` | 설정 갱신 (upsert) |
| `POST` | `/api/risk-config/reset` | default 값으로 reset |

#### RiskEvent (2 endpoint)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `GET` | `/api/risk-events` | 시계열 페이징 (`?userId&strategyId&rule&verdict&from&to&page&size`) |
| `GET` | `/api/risk-events/{id}` | 단건 |

#### Position 조회 (2 endpoint)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `GET` | `/api/positions/current` | 현재 보유 포지션 목록 |
| `GET` | `/api/positions/history` | 청산 이력 페이징 |

### 2. 도메인

`RiskConfig` · `RiskEvent` · `RiskGatewayResult` sealed · `Position` · `TradingMode` enum · `RiskContext` · `RiskRule` enum (7건) · `RiskVerdict` enum + 포트 6종 (body 0 — ADR 0051).

### 3. 영속

Flyway V16(risk_config) · V17(risk_event) · V18(position ALTER) · V19(signal.risk_status ALTER)

JPA: `RiskConfigJpaEntity/Repository/Adapter` · `RiskEventJpaEntity/Repository/Adapter` · `PositionJpaEntity/Repository/Adapter` · `SignalRiskStatusJpaAdapter`

### 4. Kafka

| 토픽 | 방향 | 발행자 | 소비자 |
|------|------|-------|-------|
| `signal.generated.v1` | consumer→consumer | `SignalKafkaProducer` | `SignalGeneratedKafkaListener` (groupId: magicjar-consumer-risk-gateway) |
| `risk.violation.v1` | consumer→api | `RiskViolationKafkaProducer` | `StompRiskNotifier`(api) |
| `order.intent.v1` | consumer→(T-O) | `OrderIntentKafkaProducer` | `OrderRouterKafkaListener` (consumer, v3.6.0 구현) |

STOMP: `/topic/risk.user.{userId}` — BLOCKED·AUTO_PAUSED 2 event type

### 5. UI

- `RiskConfigPage.tsx` · `RiskConfigForm.tsx` · `RiskEventListPage.tsx` · `RiskEventBadge.tsx` · `PositionPage.tsx` · `PositionCard.tsx` · `PnlBadge.tsx` · `useStompRisk.ts`

### 6. PR

- PR #351·#353·#354·#355·#356·#357

### 7. ADR

- ADR 0067·0023·0024·0042·0043·0044·0045·0051·0057·0061·0066·0007·0010·0047

### 8. 한계

- T-O 체결 연동 전까지 position 진입/청산 mock 기반 · unrealizedPnl 계산 T-O 완비 후 정확 · ALLOWED verdict 영속 0 (BLOCKED only)

### 9. NXT 영향 (ADR 0063)

- T-RISK는 KIS REST/WS 직접 호출 0건 → NXT 통합 거래시간 직접 영향 없음
- DailyCloseFinalizer 20:00 KST → `daily_pnl:{userId}:{date}` Redis evict 연동

---

## T-O — 주문 실행 (v3.6.0 신규 — ADR 0068)

> **AI 호출 0 의무** — 본 트랙 모든 컴포넌트 LLM provider import 0건 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 inline 주석 LOCK). 주문 의사결정은 **deterministic 룰만** (signal → risk → order 직진 chain).

> **KIS-first raw 인용 의무** — KisOrderHttpAdapter tr_id·endpoint·필드명 자체 추론 0. raw 출처 KDoc inline 의무 (헌법 §3). KisExecutionWsListener 26 컬럼 매핑 raw 출처 ADR 0068 §14-6 LOCK.

> **실계좌 이중 게이트** — `MAGICJAR_MODE=REAL` AND `MAGICJAR_ALLOW_REAL=true` AND `user_account.real_trading_consent_at IS NOT NULL` 3중 모두 충족 시에만 REAL 주문 발행. 하나라도 부재 시 REJECTED + audit REAL_GATE_DENIED + STOMP REJECTED.

### 1. REST

#### 주문 조회 (2 endpoint, ADR 0068 §4-1)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `GET` | `/api/orders` | 주문 의도 시계열 페이징 (`?userId&status&from&to&page&size`) |
| `GET` | `/api/orders/{id}` | 단건 상세 (request + executions + auditEvents) |

Swagger 4단 어노테이션 적용 (`@Tag` · `@Operation` · `@Parameter` · `@Schema` DTO — feedback_swagger_pattern.md 정합).

응답 DTO:
- `OrderListItemDto` — id·userId·code·side·qty·priceType·limitPrice?·status·mode·kisOrderNo?·requestedAt·completedAt?
- `OrderDetailResponse` — request + `List<OrderExecutionDto>` + `List<OrderAuditEventDto>`
- `OrderListPageResponse` — content·page·size·totalElements·totalPages

#### 주문 취소 (1 endpoint, ADR 0068 §4-1·E-1)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `POST` | `/api/orders/{id}/cancel` | 사용자 수동 취소 |

취소 가능 상태: REQUESTED·ACK·PARTIALLY_FILLED. 취소 불가(409): FILLED·CANCELLED·REJECTED·EXPIRED.
성공 시 KIS `order-rvsecncl` 호출 + status=CANCELLED + STOMP CANCELLED + order.cancelled.v1 publish.

request DTO: `CancelOrderRequestDto` — reason?: String
response DTO: `CancelOrderResponseDto` — id·status·completedAt?

#### 실거래 동의 토글 (2 endpoint, ADR 0068 §4-3·A-1)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `PUT` | `/api/user-account/real-trading-consent` | 동의 토글 (consented=true/false) |
| `GET` | `/api/user-account/real-trading-consent` | 현재 동의 상태 조회 |

SettingsModal "실거래 모드 동의" 토글 ON → `real_trading_consent_at=NOW()`.
SettingsModal OFF → `real_trading_consent_at=NULL` (REAL 주문 즉시 차단).

**총 5 endpoint** (orders 2 + cancel 1 + consent 2) — ADR 0068 §4 전체 정합.

인증: userId는 JWT/session 기반 (ADR 0046 정합). multi-tenant(ADR 0010) — 다른 사용자 order 조회 404.

### 2. 도메인

#### OrderRequest (영속 도메인 — 의제 F-1)

```kotlin
// domain/.../order/execution/OrderRequest.kt
data class OrderRequest(
    val id: Long?,
    val signalId: Long,
    val strategyId: Long,                    // strategy cross-ref
    val userId: String,
    val code: String,
    val side: OrderSide,                     // BUY · SELL
    val quantity: Int,
    val priceType: OrderPriceType,           // MARKET · LIMIT (의제 C-1)
    val limitPrice: BigDecimal?,             // LIMIT 시만 not null
    val status: OrderStatus,                 // 7단계
    val mode: TradingMode,                   // MOCK · REAL (의제 A-1 게이트 후 LOCK)
    val kisOrderNo: String?,                 // KIS ODNO (ACK 후 LOCK)
    val kisFwdgOrgno: String?,               // KIS KRX_FWDG_ORD_ORGNO (정정/취소 의무)
    val idempotencyKey: String,              // UQ (의제 H-1 자본 보호)
    val rejectReason: String?,
    val requestedAt: Instant,
    val acknowledgedAt: Instant?,
    val completedAt: Instant?,
)
```

#### OrderIntent (Kafka payload 도메인)

```kotlin
// domain/.../order/execution/OrderIntent.kt
data class OrderIntent(
    val id: Long?,
    val signalId: Long,
    val strategyId: Long,
    val userId: String,
    val code: String,
    val side: OrderSide,
    val priceType: OrderPriceType,
    val quantity: Int,
    val limitPrice: BigDecimal?,
    val idempotencyKey: String,
    val requestedAt: Instant,
)
```

#### OrderExecution (분할 체결 1건 — 의제 F-1·D-1)

```kotlin
// domain/.../order/execution/OrderExecution.kt
data class OrderExecution(
    val id: Long?,
    val orderId: Long,
    val executedQuantity: Int,      // CNTG_QTY (KIS raw §14-6)
    val executedPrice: BigDecimal,  // CNTG_UNPR
    val executedAt: Instant,        // STCK_CNTG_HOUR + today 합성
    val fee: BigDecimal?,
    val tax: BigDecimal?,
    val kisExecutionNo: String?,
)
```

#### OrderAuditEvent (감사 시계열 — 의제 F-1)

```kotlin
// domain/.../order/execution/OrderAuditEvent.kt
data class OrderAuditEvent(
    val id: Long?,
    val orderId: Long?,              // IDEMPOTENCY_SKIPPED 시 null 허용
    val userId: String,
    val eventType: OrderAuditEventType,
    val payloadJson: String,
    val createdAt: Instant,
)

enum class OrderAuditEventType {
    REAL_GATE_DENIED,       // A-1 3중 게이트 미충족
    IDEMPOTENCY_SKIPPED,    // H-1 Redis SETNX duplicate
    KILLSWITCH_DENIED,      // ADR 0043 KillSwitch ON
    KIS_ACK,                // KIS REST 성공 응답
    KIS_REJECT,             // KIS REST rt_cd != "0"
    KIS_EXCEPTION,          // KIS HTTP 5xx/timeout
    FILL_RECEIVED,          // H0STCNI0 CNTG_YN="2" 수신
    PARTIAL_FILL,           // 분할 체결 (qty 일부)
    FULL_FILL,              // 전량 체결
    KIS_CANCEL_ACK,         // order-rvsecncl 성공
    KIS_CANCEL_REJECT,      // order-rvsecncl 실패
    EXECUTION_TIMEOUT,      // 24h ACK 후 체결 미수신
}
```

#### OrderSide / OrderPriceType / OrderStatus / TradingMode enum

```kotlin
enum class OrderSide { BUY, SELL }

enum class OrderPriceType(val ordDvsn: String) {
    MARKET("01"),   // 시장가 — KIS ORD_UNPR="0"
    LIMIT("00"),    // 지정가 — KIS ORD_UNPR=limitPrice
}

enum class OrderStatus {
    REQUESTED,          // OrderRouter 진입
    ACK,                // KIS REST 성공 (kisOrderNo 수신)
    FILLED,             // H0STCNI0 qty 100% 도달
    PARTIALLY_FILLED,   // H0STCNI0 qty 일부
    REJECTED,           // KIS REST 실패 또는 RFUS_YN=Y
    CANCELLED,          // 사용자 취소 or ACPT_YN=3
    EXPIRED,            // 1차 미도입 — 자동 취소 worker용
}

enum class TradingMode { MOCK, REAL }
```

#### DenyReason sealed / KisOrderAck

```kotlin
sealed class DenyReason {
    data class RealGateDenied(val condition: String) : DenyReason()
    object IdempotencyDuplicate : DenyReason()
    object KillSwitchOn : DenyReason()
    data class KisRejected(val rtCd: String, val msg: String) : DenyReason()
}

data class KisOrderAck(
    val kisOrderNo: String,
    val kisFwdgOrgno: String,
    val rtCd: String,
    val msg: String,
)
```

#### 포트 인터페이스 (헥사고날 — ADR 0051 body 0 의무)

| 포트 | 파일 | 시그니처 (abstract만) |
|------|------|---------------------|
| `OrderRequestRepository` | `domain/.../port/OrderRequestRepository.kt` | `save` · `findById` · `findByKisOrderNo` · `findByIdempotencyKey` · `updateStatus` · `updateKisAck` · `markCompleted` · `findOpenByUserId` · `findByUser` |
| `OrderExecutionRepository` | `domain/.../port/OrderExecutionRepository.kt` | `save` · `findByOrderId` · `sumExecutedQuantityByOrder` |
| `OrderAuditEventRepository` | `domain/.../port/OrderAuditEventRepository.kt` | `save` · `findByOrderId` |
| `KisOrderPort` | `domain/.../port/KisOrderPort.kt` | `submit(intent, mode): Either<DenyReason, KisOrderAck>` · `cancel(req, mode): Either<DenyReason, Unit>` |
| `TradingModeGate` | `domain/.../port/TradingModeGate.kt` | `resolveMode(userId): Either<DenyReason.RealGateDenied, TradingMode>` |
| `OrderIdempotencyGuard` | `domain/.../port/OrderIdempotencyGuard.kt` | `acquire(key, ttlSec=60): Boolean` |
| `OrderNotifier` | `domain/.../port/OrderNotifier.kt` | `notifyRequested(req)` · `notifyFilled(req, exec, partial)` · `notifyRejected(req, reason)` · `notifyCancelled(req)` |
| `RealTradingConsentPort` | `domain/.../port/RealTradingConsentPort.kt` | `findConsentAt(userId): Instant?` · `setConsentAt(userId, at: Instant?)` |
| `KillSwitchQueryPort` | `domain/.../port/KillSwitchQueryPort.kt` | `isOn(userId): Boolean` |

**body를 가진 fun은 Repository interface 내부 금지** — caller Service/Adapter에 위치 (헌법 §6 허용 패턴 + ADR 0051).

### 3. 영속

#### Flyway V20 — order_request (ADR 0068 §6-1)

```sql
CREATE TABLE IF NOT EXISTS order_request (
    id                BIGSERIAL PRIMARY KEY,
    signal_id         BIGINT NOT NULL REFERENCES signal(id) ON DELETE RESTRICT,
    user_id           VARCHAR(40) NOT NULL,
    code              VARCHAR(8) NOT NULL,
    side              VARCHAR(4) NOT NULL CHECK (side IN ('BUY','SELL')),
    qty               NUMERIC(15,4) NOT NULL CHECK (qty > 0),
    price_type        VARCHAR(10) NOT NULL CHECK (price_type IN ('MARKET','LIMIT')),
    limit_price       NUMERIC(19,4),
    status            VARCHAR(20) NOT NULL DEFAULT 'REQUESTED'
        CHECK (status IN ('REQUESTED','ACK','FILLED','PARTIALLY_FILLED','REJECTED','CANCELLED','EXPIRED')),
    kis_order_no      VARCHAR(20),
    kis_fwdg_orgno    VARCHAR(20),
    mode              VARCHAR(4) NOT NULL CHECK (mode IN ('MOCK','REAL')),
    idempotency_key   VARCHAR(64) NOT NULL,
    reject_reason     VARCHAR(255),
    requested_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at   TIMESTAMPTZ,
    completed_at      TIMESTAMPTZ,
    CONSTRAINT uq_order_signal_user UNIQUE (signal_id, user_id),
    CONSTRAINT uq_order_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX IF NOT EXISTS idx_order_user_ts ON order_request (user_id, requested_at DESC);
CREATE INDEX IF NOT EXISTS idx_order_kis_no ON order_request (kis_order_no);
CREATE INDEX IF NOT EXISTS idx_order_status_open ON order_request (user_id, status)
    WHERE status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED');
```

#### Flyway V21 — order_execution (ADR 0068 §6-2)

```sql
CREATE TABLE IF NOT EXISTS order_execution (
    id               BIGSERIAL PRIMARY KEY,
    order_id         BIGINT NOT NULL REFERENCES order_request(id) ON DELETE CASCADE,
    executed_qty     NUMERIC(15,4) NOT NULL CHECK (executed_qty > 0),
    executed_price   NUMERIC(19,4) NOT NULL,
    executed_at      TIMESTAMPTZ NOT NULL,
    fee              NUMERIC(19,4) NOT NULL DEFAULT 0,
    tax              NUMERIC(19,4) NOT NULL DEFAULT 0,
    kis_execution_no VARCHAR(20),
    created_at       TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_execution_order ON order_execution (order_id);
```

#### Flyway V22 — order_audit_event (ADR 0068 §6-3)

```sql
CREATE TABLE IF NOT EXISTS order_audit_event (
    id          BIGSERIAL PRIMARY KEY,
    order_id    BIGINT REFERENCES order_request(id) ON DELETE CASCADE,
    user_id     VARCHAR(40) NOT NULL,
    action      VARCHAR(40) NOT NULL,
    detail_json JSONB,
    ts          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_order_audit_user_ts ON order_audit_event (user_id, ts DESC);
CREATE INDEX IF NOT EXISTS idx_order_audit_action ON order_audit_event (action);
```

#### Flyway V23 — user_account.real_trading_consent_at ALTER (ADR 0068 §6-4·A-1)

```sql
ALTER TABLE user_account
    ADD COLUMN IF NOT EXISTS real_trading_consent_at TIMESTAMPTZ;
-- NULL = 미동의 → REAL 주문 BLOCKED (3중 게이트 조건 3)
```

#### Flyway V24 — position.created_at ALTER (ADR 0051 JPA validate hotfix)

```sql
ALTER TABLE position
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW();
-- PositionJpaEntity.createdAt 필드 schema-validation 오류 수정
```

#### JPA 어댑터 (ADR 0051 헌법 §6 — Repository body fun 0건 의무)

- `api/persistence/order/OrderRequestJpaEntity.kt` + `OrderRequestJpaRepository.kt` + `OrderRequestRepositoryAdapter.kt`
  - `OrderRequestJpaRepository` — abstract fun body 0
  - body를 가진 helper (updateStatus, updateKisAck, etc.) → `OrderRequestRepositoryAdapter` 또는 `@Modifying @Query` (§6 허용 패턴)
- `api/persistence/order/OrderExecutionJpaEntity.kt` + `OrderExecutionJpaRepository.kt` + `OrderExecutionRepositoryAdapter.kt`
  - `findByOrderId(orderId)` · `sumExecutedQtyByOrderId(orderId)` — abstract fun (body 0)
- `api/persistence/order/OrderAuditEventJpaEntity.kt` + `OrderAuditEventJpaRepository.kt` + `OrderAuditEventRepositoryAdapter.kt`
  - `findByOrderIdOrderByTsAsc(orderId)` — abstract fun (body 0)
- `api/persistence/order/RealTradingConsentJpaAdapter.kt` — `user_account.real_trading_consent_at` UPDATE (body는 Adapter class에)
- `api/persistence/order/KillSwitchQueryAdapter.kt` + `KillSwitchQueryJpaRepository.kt` — ADR 0043 KillSwitch 재검증

### 4. Kafka

#### 소비 (consumer — ADR 0068 §7-1)

| 토픽 | 리스너 | groupId | 처리 내용 |
|------|-------|---------|----------|
| `order.intent.v1` | `OrderRouterKafkaListener` | `magicjar-consumer-order` | T-RISK ALLOWED → 멱등성 검사 → 3중 게이트 → KillSwitch 재검증 → order_request INSERT → KIS REST → ACK/REJECT |

KafkaListener 설정 LOCK: `enable.auto.commit=false`, `ack-mode=MANUAL_IMMEDIATE`, `max.poll.records=1`, `concurrency=1` per partition, `isolation.level=read_committed`.

#### 발행 (consumer — ADR 0068 §9-2)

| 토픽 | 발행자 | retention | 내용 |
|------|-------|---------|------|
| `order.requested.v1` | `OrderRequestedKafkaProducer` | 30d | 주문 접수 통보 (step 6) |
| `order.filled.v1` | `OrderFilledKafkaProducer` | 30d | 체결 통보 (KisExecutionWsListener) |
| `order.rejected.v1` | `OrderRejectedKafkaProducer` | 30d | 주문 거절 (step 3·4·7) |
| `order.cancelled.v1` | `OrderCancelledKafkaProducer` | 30d | 취소 통보 |

4 토픽 등록: `OrderTopicConfig.kt` (`consumer/config/`) — KafkaAdmin NewTopic 4건.

#### STOMP (api — ADR 0068 G-1)

단일 토픽 `/topic/order.user.{userId}` + 4 event discriminator (`type` JSON 필드):

```json
// REQUESTED event
{ "type": "REQUESTED", "orderId": 1, "code": "005930", "side": "BUY", "qty": 10, "mode": "MOCK", "requestedAt": "..." }

// FILLED event
{ "type": "FILLED", "orderId": 1, "executedQty": 10, "executedPrice": 73000, "executedAt": "...", "partial": false }

// REJECTED event
{ "type": "REJECTED", "orderId": 1, "reason": "KIS_REJECT:90020:..." }

// CANCELLED event
{ "type": "CANCELLED", "orderId": 1, "completedAt": "..." }
```

#### 6 룰 평가 흐름 recap (consumer — ADR 0068 §14-2)

```
OrderRouterKafkaListener 12 step (ADR 0068 §14-2 LOCK):
1. deserialize  2. Redis SETNX 멱등성  3. TradingModeGate 3중 게이트
4. KillSwitch 재검증  5. order_request INSERT  6. STOMP+Kafka 병렬 발행
7. KisOrderHttpAdapter.submit (1회 retry)  8. ACK UPDATE  9. KIS_ACK audit
10. (체결 통보 별도 KisExecutionWsListener)  11. Kafka MANUAL commit  12. Micrometer counter
```

#### KIS-first raw 인용 인벤토리 (ADR 0068 §7-5 · 헌법 §3 LOCK)

| 컴포넌트 | raw 출처 | line | tr_id |
|---------|---------|------|-------|
| KisOrderHttpAdapter.submit (BUY) | `examples_user/domestic_stock/domestic_stock_functions.py` | L10097~L10213 | TTTC0012U(real) / VTTC0012U(mock) |
| KisOrderHttpAdapter.submit (SELL) | 동상 | 동상 | TTTC0011U(real) / VTTC0011U(mock) |
| KisOrderHttpAdapter.cancel | 동상 (`def order_rvsecncl`) | L10771~L10884 | TTTC0013U(real) / VTTC0013U(mock) |
| KisExecutionWsListener | `examples_user/domestic_stock/domestic_stock_functions_ws.py` (`def ccnl_notice`) | L389~L446 | H0STCNI0(real) / H0STCNI9(mock) |

### 5. UI

#### 주문 내역 (`/orders`)

- `frontend/src/pages/orders/OrdersPage.tsx` — 주문 목록 (무한스크롤 or 페이징)
- `frontend/src/pages/orders/OrderDetailPage.tsx` (`/orders/:id`) — 단건 상세
- `frontend/src/components/order/OrderStatusBadge.tsx` — status 5단 색상 (REQUESTED·ACK·FILLED·REJECTED·CANCELLED)
- `frontend/src/components/order/OrderCancelButton.tsx` — status ∈ open 시만 활성 (REQUESTED·ACK·PARTIALLY_FILLED)
- `frontend/src/components/order/ExecutionTable.tsx` — 분할 체결 1:N 시각화
- `frontend/src/components/order/AuditEventTimeline.tsx` — 감사 이벤트 시계열

#### SettingsModal 보강 (의제 A-1)

- SettingsModal "실거래 모드 동의" 토글 — consented=true 시 경고 모달 ("실 자본 변동 발생 — KIS 실계좌 주문 발행")
- `RealTradingConsentToggle.tsx` 컴포넌트

#### 실시간 알림 (STOMP)

- `frontend/src/hooks/useStompOrder.ts` — `/topic/order.user.{userId}` STOMP subscribe
  - REQUESTED → 파란 토스트
  - FILLED → 녹색 토스트 (체결 종목·수량·단가)
  - REJECTED → 빨간 토스트 (사유 표시)
  - CANCELLED → 회색 토스트

#### REST hooks

- `frontend/src/hooks/useOrders.ts` — 목록 (TanStack Query 무한스크롤)
- `frontend/src/hooks/useOrder.ts` — 단건
- `frontend/src/hooks/useCancelOrder.ts` — POST cancel mutation
- `frontend/src/hooks/useRealTradingConsent.ts` — GET/PUT consent

routing 확장: `/orders` · `/orders/:id` + AppHeader 메뉴 1건 (주문 내역) 추가.

feedback_no_mock_data.md 정합 — 주문 0건 시 "주문 내역 없음" 명시 표시.

### 6. PR

- PR #371 (architect plan — ADR 0068 Proposed → Accepted)
- PR #372 (strategy-engine — Phase 1: domain 12 + port 9 + OrderRouterKafkaListener 12 step + OrderIdempotencyGuardImpl + TradingModeGateImpl 3중 게이트 + KisOrderHttpAdapter raw KDoc + KisExecutionWsListener 26 컬럼 + 4 Kafka producer + OrderTopicConfig 4 topic + 단위 테스트 ~15건)
- PR #373 (backend-core — Phase 2: Flyway V20~V24 + JPA 3쌍 + RealTradingConsentJpaAdapter + KillSwitchQueryAdapter + OrderQueryService + OrderCancelService + RealTradingConsentService + REST 5 endpoint + StompOrderNotifier 4 event + 단위 테스트 ~8건)
- PR #374 (frontend — Phase 3: /orders·/orders/:id 2 페이지 + 6 컴포넌트 + 4 API hook + useStompOrder + RealTradingConsentToggle 이중 confirm)
- PR #375 (v3.6.0 release 태그)

### 7. ADR

- ADR 0068 — **T-O 주문 실행 (A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택 2026-05-13 — v3.6.0 신규 권위)**
- ADR 0067 — T-RISK (order.intent.v1 publish → 본 ADR 진입점 LOCK)
- ADR 0066 — T-SIG (signal → risk → order 체인 terminus)
- ADR 0023 — Signal Engine 룰 온리 (AI 호출 0 — 본 ADR 동일 정책 계승)
- ADR 0024 — Risk Guard chain 6단 (KillSwitch 재검증 — ADR 0043 정합)
- ADR 0042 — Position Bootstrap (PositionUpdateAdapter 합류점)
- ADR 0043 — KillSwitch (OrderRouterKafkaListener step 4 재검증 의무)
- ADR 0044 — Notification System (order.requested/filled/rejected/cancelled → Slack 후행 진입점)
- ADR 0045 — Graceful Shutdown (OrderRouterKafkaListener + KisExecutionWsListener SmartLifecycle)
- ADR 0046 — Onboarding (UserKisCredential REAL/MOCK 2종 fetch — KisOrderHttpAdapter)
- ADR 0048 — KIS WS 구독 한도 (H0STCNI0 사용자별 1 추가 — 현재 11건, margin 30건 OK)
- ADR 0051 — Repository default fun 함정 (OrderRequest/Execution/AuditEvent JpaRepository body 0)
- ADR 0057 — KIS-only (KisOrderHttpAdapter raw KDoc inline 의무 — tr_id·endpoint 자체 추론 0)
- ADR 0061 — Backtest 결과 영속 패턴 (3 테이블 + REST endpoint 패턴 baseline — 본 ADR F-1 동일)
- ADR 0007 — Kafka 이벤트 backbone (order.requested/filled/rejected/cancelled 4 신규)
- ADR 0010 — multi-tenant (userId 격리 의무 — order_request·execution·audit·STOMP)

### 8. 한계

- **정정(RVSE_CNCL_DVSN_CD="01") 미도입** — 1차 MVP 사용자 취소→재주문 2 step. 후속 backlog §46
- **자동 취소 worker 미도입** — 지정가 미체결 시간 초과 자동 취소 0건. 후속 backlog §45
- **D-2 polling fallback 미도입** — H0STCNI0 미수신 시 24h 후 EXECUTION_TIMEOUT audit only. 후속 backlog §44
- **KIS API Rate Limit burst 방어 미도입** — 동시 10+ 시그널 burst 시 token bucket queue 없음. 후속 backlog §47
- **수수료·세금 계산 0** — order_execution.fee·tax 현재 0 default. KIS 별도 수수료 조회 API 미연동
- **T-RECON 연동 미완** — order.filled.v1 발행만 (ADR 0041 — Broker Reconciliation 후행 합류점)
- **T-NOTIFY 연동 미완** — 4 Kafka 토픽 발행만 (ADR 0044 — order.filled.v1 → Slack 후행)

### 자본 보호 risk 매트릭스 (ADR 0068 §14-4)

| risk | 인지 | 대응 |
|------|------|------|
| KIS REST 5xx 일시 장애 | HTTP status | 1회 retry (500ms) → fail 시 REJECTED + KIS_EXCEPTION audit |
| KIS REST rt_cd != "0" | rt_cd 필드 | REJECTED + reject_reason=rt_cd:msg + STOMP REJECTED |
| 중복 주문 (idempotency 동일) | Redis SETNX false | IDEMPOTENCY_SKIPPED audit (STOMP X — 사용자 노이즈 회피) |
| Redis 장애 (SETNX 예외) | RedisConnectionException | 즉시 REJECTED + STOMP REJECTED (자본보다 가용성 우선 X) |
| 3중 게이트 실패 | DenyReason.RealGateDenied | REJECTED + REAL_GATE_DENIED audit |
| KillSwitch ON | KillSwitchQueryPort.isOn() | REJECTED + KILLSWITCH_DENIED audit |
| H0STCNI0 미수신 24h | order_request ACK + executions=0 | EXECUTION_TIMEOUT audit + 사용자 알림 (자동 cancel X) |
| 분할 체결 race | 동시 H0STCNI0 2건 | sumExecutedQuantity 비교 후 status 결정 (멱등) |
| executions 합 > quantity | sumExecutedQuantity 비교 | EXECUTION_OVERFLOW audit + position 갱신 skip |

---

## CandleAggregator — 합산 가공 도메인 (ADR 0062 — v3.3.0 이월)

(v3.5.0 이월 — 변경 없음)

---

## AggregateBackfillRunner — cold-start 합산 후속 (ADR 0062 §4-5, 이월)

(v3.5.0 이월 — 변경 없음)

---

## 공통 보안·설정 피처

(v3.5.0 이월 — 변경 없음)

---

## ADR 의존 요약 (트랙 전체 — v3.6.0 갱신)

| ADR | 영향 트랙 |
|-----|---------|
| ADR 0004 | 모든 트랙 (모듈 경계) |
| ADR 0007 | T-W1·T-S1·T-FANOUT·T-SIG·T-RISK·**T-O** (order.requested/filled/rejected/cancelled 4 신규) |
| ADR 0010 | T-SIG·T-RISK·**T-O** (userId 격리 의무 — order_request·execution·audit·STOMP) |
| ADR 0019 | T-S1·T-IND·T-PAT·T-BT·CandleAggregator·T-FANOUT |
| ADR 0021 | T-IND·T-SIG |
| ADR 0022 | T-DSL·T-SIG |
| ADR 0023 | T-DSL·T-BT·T-FANOUT·T-SIG·T-RISK·**T-O** (룰 온리 + AI 호출 0 모두 계승) |
| ADR 0024 | T-RISK·**T-O** (Risk Guard chain 6단 — KillSwitch 재검증 의무) |
| ADR 0028 | T-W1·T-S1 |
| ADR 0029 | T-S1·CandleAggregator·T-FANOUT·T-SIG |
| ADR 0037 | T-S1·CandleAggregator·**T-O** (주문 burst 5건 × 200ms — Rate Limit 영향 검증) |
| ADR 0040 | T-W1 |
| ADR 0041 | **T-O** (order.filled.v1 → T-RECON 후행 합류점) |
| ADR 0042 | T-RISK·**T-O** (PositionUpdateAdapter 합류점) |
| ADR 0043 | T-RISK·**T-O** (KillSwitch 재검증 의무) |
| ADR 0044 | T-RISK·**T-O** (order.requested/filled/rejected/cancelled → Notification 후행) |
| ADR 0045 | T-SIG·T-RISK·**T-O** (Graceful Shutdown — OrderRouterKafkaListener·KisExecutionWsListener SmartLifecycle) |
| ADR 0046 | 보안·**T-O** (UserKisCredential REAL/MOCK 2종 fetch) |
| ADR 0047 | T-DSL·T-BT·T-SIG (T-O risk-eval-pool 불필요 — KIS REST path) |
| ADR 0048 | T-S1·T-FANOUT·**T-O** (KIS WS H0STCNI0 추가 구독 한도 영향) |
| ADR 0051 | 모든 JPA Repository·**T-O** (OrderRequest/Execution/AuditEvent JpaRepository body 0 의무) |
| ADR 0054 | T-PAT |
| ADR 0055 | T-DSL |
| ADR 0056 | T-BT |
| ADR 0057 | T-S1·CandleAggregator·T-SIG·T-RISK·**T-O** (KIS-only 의무 — raw KDoc inline) |
| ADR 0061 | T-BT·T-RISK·**T-O** (3 테이블 + REST endpoint 패턴 baseline) |
| ADR 0062 | CandleAggregator·T-FANOUT·T-SIG |
| ADR 0063 | T-S1·CandleAggregator·T-FANOUT·T-SIG |
| ADR 0064 | T-FANOUT·T-SIG |
| ADR 0066 | T-SIG·T-RISK·**T-O** (signal → risk → order 체인 terminus) |
| ADR 0067 | T-RISK·**T-O** (order.intent.v1 publish → 본 ADR 진입점 LOCK) |
| ADR 0068 | **T-O** (주문 실행 — A-1·B-1·C-1·D-1·E-1·F-1·G-1·H-1 채택 — v3.6.0 신규 권위) |

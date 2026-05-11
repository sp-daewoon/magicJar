# v3.2.2 기능 명세 (트랙별 표준 9 항목)

- baseline: v3.2.2
- 작성일: 2026-05-11
- 작성자: backend-core (bot+backend-core@magicjar.local)
- SoT: `docs/architecture/master-architecture.md` + ADR INDEX (`_workspace/arch/adr/README.md`)

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
- ADR 0028 — Watchlist 기반 Ingestion Worker (cold-start 트리거 인터페이스)
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
| `GET` | `/api/symbols/{code}` | 현재가·PER·PBR·시총·업종 (Redis 1순위·KIS REST 2순위) |
| `GET` | `/api/symbols/{code}/candles` | 캔들 시계열 (DB + Redis current 병합, 페이지네이션) |
| `POST` | `/api/symbols/{code}/orderbook/subscribe` | 호가 구독 시작 |
| `POST` | `/api/symbols/{code}/orderbook/unsubscribe` | 호가 구독 해제 |

### 2. 도메인

- `domain/market/candle/Candle.kt` — `data class Candle(code, interval, ts, openRaw, highRaw, lowRaw, closeRaw, openAdj, highAdj, lowAdj, closeAdj, volume, source)`
- `domain/market/candle/CandleInterval.kt` — enum 11종 (1m·2m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y)
- `domain/market/port/CandleRepository.kt` — `saveAll` · `countByCodeAndInterval` · `countByCodeAndIntervalIn` · `findByCodeAndInterval`
- `domain/market/port/QuoteCachePort.kt` — `getLatestPrice` · `putLatestPrice`
- `domain/market/port/KisRestPort.kt` — `fetchCandles` · `fetchPrice` · `fetchPriceMetrics`

### 3. 영속

- `api/persistence/CandleJpaEntity.kt` — JPA 엔티티
- `api/persistence/CandleJpaRepository.kt` — Spring Data JPA (ADR 0051 준수 — body fun 0건)
- `consumer/persistence/ConsumerCandleJpaEntity.kt` + `ConsumerCandleJpaRepository.kt` — consumer 모듈 어댑터
- Flyway: `V*__create_candle.sql` — `candle(id, code, interval, ts, ...)` UQ(code, interval, ts), CHECK(interval IN 11종)

### 4. Kafka

| 토픽 | 방향 | 설명 |
|------|------|------|
| `symbol.detail.command.v1` | api→consumer | ENTER/EXIT 호가 구독 명령 |
| `market.tick.v1` | consumer→api | 실시간 체결가 (STOMP fanout) |
| `market.candle.minute.closed.v1` | consumer→api | 1분봉 closing (STOMP fanout) |
| `market.orderbook.v1` | consumer→api | 호가 스냅샷 (STOMP fanout) |

STOMP 토픽:
- `/topic/market.tick.{code}` — 현재가·등락 실시간 (1초 throttle)
- `/topic/market.candle.minute.{code}` — 1분봉 closing
- `/topic/market.orderbook.{code}` — 호가 스냅샷

### 5. UI

- `frontend/src/pages/Symbol/SymbolPage.tsx` — 종목 상세 페이지
- `frontend/src/pages/Symbol/SymbolPriceCard.tsx` — 현재가·등락 표시 (STOMP 단일 source — dogfooding 2026-05-11 fix)
- `frontend/src/pages/Symbol/SymbolChart.tsx` + `LightweightChart.tsx` — LWC v4 차트 (IntervalToggle 10종)
- `frontend/src/pages/Symbol/IntervalToggle.tsx` — 1m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y 10종

### 6. PR

- PR #210 (T-S1 R1 SymbolController scaffold + CandleQueryService)
- PR #215 (T-S1 R2 Redis quote:last + STOMP tick forwarder)
- PR #289 (frontend STOMP tick sync — dogfooding fix)
- PR #291 (SymbolPriceCard 등락 STOMP 단일 source — hotfix)

### 7. ADR

- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (quote:last, candle:current)
- ADR 0048 — KIS WS 41 slot 한도 (호가 LRU)
- ADR 0057 — KIS-only 데이터 출처 (Yahoo 폐기 2026-05-11)
- ADR 0062 — 5m·15m·30m·90m·1h 합산 가공 (ADR 0062 §4-4)
- ADR 0063 — NXT 통합 거래시간 (10종 IntervalToggle X축 범위)

### 8. 한계

- 호가 UI 분리 팝업 미구현 (현재 toast alert stub)
- 차트 그리기 도구(Draw) 미구현 (T-LWC W5)
- interval 2m 비활성 (KIS 미제공 — ADR 0019 CHECK constraint 보존만)

### 9. NXT 영향 (ADR 0063)

- `LightweightChart.tsx` `isIntradayInterval()` NXT 08:00~20:00 ts 입력 시 X축 자동 확장 정합 (PR #302)
- `AGGREGATED_INTERVALS` 5종(5m·15m·30m·90m·1h) 활성화 + 빈 응답 graceful

---

## T-IND — Indicator (기술적 지표 80종)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/indicators` | 80지표 계산 (code·name·interval·params·from·to·adjusted) |

### 2. 도메인

- `domain/indicator/IndicatorEngine.kt` — 지표 엔진 인터페이스
- `domain/indicator/IndicatorCatalog.kt` — 80종 지표 카탈로그 (KIS Strategy Builder 80지표 + native 구현)
- `domain/indicator/IndicatorSpec.kt` — `data class IndicatorSpec(name, params)`
- `domain/indicator/IndicatorResult.kt` — `data class IndicatorResult(spec, series, source)`
- `domain/indicator/IndicatorPoint.kt` — `data class IndicatorPoint(time: Instant, values)`
- `domain/indicator/port/IndicatorSource.kt` — 지표 소스 포트 인터페이스

### 3. 영속

- DB 직접 영속 없음 — Redis 캐시만 (`indicator:cache:{code}:{interval}:{name}:{paramsHash}`)
- 분봉 TTL 300s / 일봉+ TTL 86400s

### 4. Kafka

없음 (요청 기반 동기 계산).

### 5. UI

- `frontend/src/pages/Symbol/IndicatorPanel.tsx` — 지표 파라미터 입력 + 시계열 시각화 (T-IND 트랙 결과물)

### 6. PR

- PR #230 (T-IND R1 IndicatorController + native 5종: MA·EMA·MACD·RSI·BB)
- PR #240 (T-IND R2 KIS Strategy Builder 75종 + Redis 캐시)

### 7. ADR

- ADR 0021 — 지표 소스 추상화 (native vs KIS Strategy Builder dual source)
- ADR 0019 — 캔들 DB로부터 지표 입력 (UQ code+interval+ts)

### 8. 한계

- consumer 모듈 IndicatorEngineConfig 빈 등록 완료, 실시간 tick 기반 지표 계산 미구현 (T-SIG 트랙)
- adjusted=true (수정 주가) 경로 DB에 openAdj·closeAdj 없으면 raw fallback

---

## T-DSL — Strategy DSL

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/strategy` | 전략 등록 (DSL 텍스트 → AST → DB) |
| `GET` | `/api/strategy` | 전략 목록 (userId 필터) |
| `GET` | `/api/strategy/{id}` | 전략 단건 조회 |
| `DELETE` | `/api/strategy/{id}` | 전략 삭제 |
| `POST` | `/api/strategy/validate` | DSL 식 검증 (DB 저장 없음) |
| `PUT` | `/api/strategy/{id}/enable` | auto-disabled 전략 재활성화 |
| `POST` | `/api/strategy/{id}/evaluate` | 전략 평가 → BUY/SELL/HOLD (stub) |
| `POST` | `/api/ai/dsl/translate` | 자연어 → DSL 변환 (AI 보조) |

### 2. 도메인

- `domain/strategy/Strategy.kt` — 전략 aggregate
- `domain/strategy/dsl/DslStrategyDefinition.kt` + 하위 AST 노드 (Condition·CompositeCondition·IndicatorRef·LiteralValue·ArithmeticExpr·DslNode)
- `domain/strategy/StrategyComplexity.kt` + `ComplexityClass.kt` — AST depth·노드 수·지표 호출 수·복잡도 등급
- `domain/strategy/Signal.kt` — `enum Signal { BUY, SELL, HOLD }`
- `domain/strategy/port/StrategyRepositoryPort.kt` — 포트 인터페이스
- `domain/backtest/*` — 백테스트 도메인 (BacktestRequest·Result·Trade·Position·Performance·Exception)

### 3. 영속

- `api/persistence/StrategyJpaEntity.kt` + `StrategyJpaRepository.kt`
- Flyway: `V*__create_strategy.sql` — `strategy(id, user_id, name, entry_dsl_json, exit_dsl_json, complexity_json, disabled_reason, last_disabled_at)`

### 4. Kafka

- `consumer/application/strategy/JvmStrategyEvaluator` — Kafka `market.tick.v1` 소비 → DSL 평가 → Signal 발행 (T-SIG W3 트랙, 현재 partial)

### 5. UI

- `frontend/src/pages/Strategy/StrategyBuilderPage.tsx` — DSL 빌더 화면
- `frontend/src/pages/Strategy/DslEditor.tsx` — 코드 에디터 (Monaco 또는 textarea)

### 6. PR

- PR #220 (T-DSL R1 도메인 AST + StrategyController scaffold)
- PR #225 (T-DSL R2 KisYamlDslParser + DB 영속)

### 7. ADR

- ADR 0022 — KIS Strategy Builder 호환 DSL YAML superset
- ADR 0023 — 시그널 엔진 룰 온리 (AI 매매 결정 0)
- ADR 0047 — DSL 평가 sandbox (timeout 1000ms·누적 50회 abort)
- ADR 0055 — Strategy Builder 컴포넌트 스택

### 8. 한계

- `POST /api/strategy/{id}/evaluate` stub (JvmStrategyEvaluator 미완성 — strategy-engine 담당)
- 전략 import (`*.kis.yaml`) UI 업로드 미구현 (T-SB W2 트랙)
- 전략 공유·클론 기능 미구현 (backlog)

---

## T-PAT — Pattern Recognition (캔들스틱 패턴 57종)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/patterns` | 57종 패턴 인식 (code·interval·patterns·from·to) |

### 2. 도메인

- `domain/pattern/port/PatternRecognizer.kt` — 포트 인터페이스
- `domain/pattern/PatternMatch.kt` — `data class PatternMatch(pattern, startTime, endTime, direction, confidence, params)`
- `pattern-engine/JvmPatternRecognizer.kt` — 57종 구현체 (Pure Kotlin, Spring 의존 0 — ADR 0054)

### 3. 영속

- DB 직접 영속 없음 — Redis 캐시 (`pattern:cache:{code}:{interval}:{...}`)
- 분봉 TTL 300s / 일봉+ TTL 86400s

### 4. Kafka

없음 (요청 기반 동기 계산).

### 5. UI

- `frontend/src/pages/Symbol/PatternOverlay.tsx` — LWC v4 차트 위 패턴 마커 오버레이

### 6. PR

- PR #241 (T-PAT R1 PatternController + pattern-engine JvmPatternRecognizer 10종 B-1)
- PR #261 (T-PAT R2 57종 완성 + Redis 캐시 + ConditionalOnProperty 제거)

### 7. ADR

- ADR 0054 — pattern-engine 모듈 분리 (Spring 의존 0 Pure Kotlin)
- ADR 0019 — 캔들 DB (PatternController CandleJpaRepository 직접 사용 — ADR 0051 준수)

### 8. 한계

- 57종 중 R2 stub 47건 → `direction=NEUTRAL` → 결과 미포함 (graceful)
- 실시간 패턴 감지 (tick 기반) 미구현 (T-SIG W3)
- PatternOverlay 마커 클릭 상세 팝업 미구현

---

## T-BT — Backtest Engine (ADR 0056)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/backtest/run` | DSL 전략 백테스트 (universe·interval·from·to·initialCapital·tax/commission/slippage 모델) |

### 2. 도메인

- `domain/backtest/BacktestRequest.kt` — 백테스트 요청 파라미터
- `domain/backtest/BacktestResult.kt` — sealed class `Success`·`Aborted`
- `domain/backtest/BacktestTrade.kt` — 체결 기록
- `domain/backtest/BacktestPosition.kt` — 포지션
- `domain/backtest/BacktestPerformance.kt` — 성과 지표 (totalReturn·CAGR·maxDrawdown·Sharpe·winRate)
- `domain/backtest/LookaheadGuard.kt` — 미래 데이터 참조 방지
- `domain/backtest/port/BacktestEngine.kt` — 포트 인터페이스
- `domain/backtest/port/BacktestCommissionModel.kt` — 수수료 포트
- `domain/backtest/port/BacktestSlippageModel.kt` — 슬리피지 포트
- `domain/backtest/port/BacktestTaxModel.kt` — 세금 포트
- `domain/backtest/port/StrategyEvaluatorPort.kt` — 전략 평가 포트

### 3. 영속

- DB 직접 영속 없음 (결과는 response JSON only)
- 캔들 데이터는 `CandleJpaRepository` (api 모듈) 조회

### 4. Kafka

없음.

### 5. UI

- `frontend/src/pages/Backtest/BacktestPage.tsx` — 백테스트 UI
- `frontend/src/pages/Backtest/BacktestChart.tsx` — equity curve + drawdown 차트 (LWC v4)
- `frontend/src/pages/Backtest/BacktestResultTable.tsx` — 체결 목록 + 성과 테이블

### 6. PR

- PR #251 (T-BT R1 BacktestController + BacktestService + JvmBacktestEngine scaffold)
- PR #260 (T-BT R2 commission·slippage·tax 모델 + BacktestPerformanceCalculator)

### 7. ADR

- ADR 0056 — 백테스트 엔진 아키텍처 (sandbox·LookaheadGuard·ADR 0047 정합)
- ADR 0047 — DSL 평가 sandbox (timeout 1000ms·누적 50회 abort)
- ADR 0023 — 룰 기반 온리 (AI 호출 0)

### 8. 한계

- `HoldStrategyEvaluatorStub.kt` — JvmStrategyEvaluator 미완성 시 stub (항상 HOLD)
- 다중 전략 동시 백테스트 미구현
- 백테스트 결과 저장(TradeJournalEntry) 미구현 (T-J W3 트랙)

---

## CandleAggregator — 합산 가공 도메인 (ADR 0062)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/admin/aggregate/{code}` (port 8082) | 특정 종목 수동 합산 트리거 |
| `POST` | `/admin/aggregate/all` (port 8082) | 전 watchlist 합산 트리거 |

### 2. 도메인

- `domain/market/candle/CandleAggregator.kt` — Pure Kotlin 합산 엔진 (Spring 의존 0)
  - 입력: `List<Candle>` (1m, ts ASC 정렬)
  - 출력: `List<Candle>` (target interval — 5m·15m·30m·90m·1h)
  - 공식: `OHLCV = (first.open, max(highs), min(lows), last.close, sum(volumes))`
  - KIS raw 출처 inline 주석: FHKST03010230 `stck_bsop_date·stck_cntg_hour·stck_oprc·stck_hgpr·stck_lwpr·stck_prpr·cntg_vol`

### 3. 영속

- `candle` 테이블 동일 스키마 (5m·15m·30m·90m·1h ON CONFLICT DO NOTHING — idempotent INSERT)
- ADR 0062 §9 NXT 기준 DB ROW 예측 (10종목 × 30 거래일):

| Interval | DB ROW 예측 (NXT 통합) |
|----------|----------------------|
| 5m | 43,200 (144 bar/일) |
| 15m | 14,400 (48 bar/일) |
| 30m | 7,200 (24 bar/일) |
| 90m | 2,400 (8 bar/일) |
| 1h | 3,600 (12 bar/일) |
| **합** | **~71K rows** |

### 4. Kafka

- `market.candle.minute.closed.v1` 소비 → boundary 마감 검사 → Redis `candle:current:{code}:{interval}` 갱신 (TickToMinuteAggregator 확장 예정)

### 5. UI

- IntervalToggle 5종 활성화 (5m·15m·30m·90m·1h) — `SymbolChart.tsx` `AGGREGATED_INTERVALS` 상수 정합

### 6. PR

- PR #298 (CandleAggregator 도메인 + AggregateBackfillRunner + ColdStartExecutor 확장)
- PR #302 (DailyCloseFinalizer 20:00 KST + CandleBootstrapRunner 5m 검사 분기)

### 7. ADR

- ADR 0062 — 5m·15m·30m·90m·1h 합산 가공 계획 (Accepted 2026-05-11)
- ADR 0063 — NXT 통합 (§9 결합 영향 — boundary 매트릭스 NXT 확장)
- ADR 0019 — 캔들 저장 정책 (§2-5 합산 revoke — CandleAggregator 부활)
- ADR 0029 — Redis 키 매트릭스 (5m~1h boundary 캐시 5건 추가)

### 8. 한계

- TickToMinuteAggregator 장중 boundary 마감 → Redis `candle:current` 갱신 로직 미구현 (TODO: T-SIG 트랙 선행 후 연결)
- 합산 결과 staleness: 1m UPDATE/DELETE 시 재계산 의무 (현재 append-only 가정)

### 9. NXT 영향 (ADR 0063)

- boundary 정렬: 08:00~20:00 KST (기존 09:00~15:30 확장)
- `DailyCloseFinalizer` 20:00 KST (구 16:00 KST → 변경)
- Redis `candle:current:{code}:Nm` 08:00 시작 / 20:00 evict

---

## AggregateBackfillRunner — cold-start 합산 후속 (ADR 0062 §4-5)

### 1. REST

별도 엔드포인트 없음 (ColdStartExecutor·CandleBootstrapRunner에서 내부 호출).

### 2. 도메인

- `consumer/aggregation/AggregateBackfillRunner.kt` — 1m 전량 조회 → CandleAggregator → 5m·15m·30m·90m·1h INSERT
- `consumer/aggregation/AggregateAdminController.kt` (port 8082) — 수동 트리거 (admin endpoint)

### 3. 영속

- `CandleRepository.findByCodeAndInterval(code, ONE_MIN)` 전량 조회 → `CandleAggregator.aggregate()` → `CandleRepository.saveAll()` (ON CONFLICT DO NOTHING)

### 4. Kafka

없음.

### 5. UI

없음 (admin endpoint).

### 6. PR

- PR #298 (AggregateBackfillRunner 신규 + ColdStartExecutor 확장 + CandleBootstrapRunner 5m 검사 분기)

### 7. ADR

- ADR 0062 §4-5 — migration plan (1m 이미 적재 종목 backfill)
- ADR 0051 — `CandleRepository.findByCodeAndInterval` abstract fun (body 0 — 헌법 §6 정합)

### 8. 한계

- backfill 진행 중 중복 실행 방지 분산 lock 미구현 (단일 서버 환경에서 중복 위험 낮음)
- 종목 50+ 시 cold-start 시간 증가 → `ColdStartExecutor` thread pool 2개 한계 (backlog §28)

---

## 공통 보안·설정 피처

### KIS 키 관리

- `api/controller/OnboardingController.kt` — `POST /api/onboarding` KIS appKey·appSecret 입력
- `api/secret/AesGcmCipher.kt` — AES-256-GCM 암호화 (DB 저장)
- `domain/user/UserKisCredential.kt` — 크리덴셜 도메인
- `consumer/config/SecretConfig.kt` — KIS 키 복호화 + 환경변수 fallback
- prod credential 단일 채널 (ADR 0057 + `feedback_kis_key_realonly_for_data`)

### 온보딩 가드

- `api/config/OnboardingGuardFilter.kt` — KIS 키 미등록 시 401 ONBOARDING_REQUIRED
- `api/controller/OnboardingController.kt` — `POST /api/onboarding/validate` KIS 키 유효성 검증 (ADR 0046)

### KIS Rate Limit

- ADR 0037 — 초당 최대 10 TPS, 50ms 마진 강제
- `consumer/infrastructure/kis/KisRestCandleAdapter.kt` — 페이지 간 50ms delay
- `consumer/infrastructure/kis/KisAuthAdapter.kt` — Redis 분산 lock (ADR 0038 — `kis:oauth:lock` 키)

---

## ADR 의존 요약 (트랙 전체)

| ADR | 영향 트랙 |
|-----|---------|
| ADR 0004 | 모든 트랙 (모듈 경계) |
| ADR 0007 | T-W1·T-S1 (Kafka backbone) |
| ADR 0019 | T-S1·T-IND·T-PAT·T-BT·CandleAggregator |
| ADR 0021 | T-IND (지표 소스 추상화) |
| ADR 0022 | T-DSL (KIS 호환 DSL) |
| ADR 0023 | T-DSL·T-BT (룰 온리) |
| ADR 0028 | T-W1·T-S1 (Watchlist Ingestion Worker) |
| ADR 0029 | T-S1·CandleAggregator (Redis 캐시) |
| ADR 0037 | T-S1·CandleAggregator (KIS Rate Limit) |
| ADR 0040 | T-W1 (Watchlist-first UI 흐름) |
| ADR 0046 | 보안 (온보딩 키 등록) |
| ADR 0047 | T-DSL·T-BT (DSL sandbox) |
| ADR 0048 | T-S1 (KIS WS 41 slot) |
| ADR 0051 | 모든 JPA Repository (default fun 함정) |
| ADR 0054 | T-PAT (pattern-engine 모듈 분리) |
| ADR 0055 | T-DSL (Strategy Builder 컴포넌트 스택) |
| ADR 0056 | T-BT (백테스트 엔진 아키텍처) |
| ADR 0057 | T-S1·CandleAggregator (KIS-only 데이터 출처) |
| ADR 0062 | CandleAggregator·AggregateBackfillRunner |
| ADR 0063 | T-S1·CandleAggregator (NXT 통합) |

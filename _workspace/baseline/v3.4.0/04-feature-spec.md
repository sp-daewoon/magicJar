# v3.4.0 기능 명세 (트랙별 표준 9 항목)

- baseline: v3.4.0
- 작성일: 2026-05-12
- 작성자: backend-core (bot+backend-core@magicjar.local)
- SoT: `docs/architecture/master-architecture.md` + ADR INDEX (`_workspace/arch/adr/README.md`)
- 기준: v3.3.0 04-feature-spec.md 전체 이월 + v3.3.1 T-BT 영속 + v3.4.0 T-SIG 트랙 추가 (ADR 0061·ADR 0066 채택)

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
- `domain/market/port/CandleRepository.kt` — `saveAll`·`countByCodeAndInterval`·`countByCodeAndIntervalIn`·`findByCodeAndInterval`
- `domain/market/port/QuoteCachePort.kt` — `getLatestPrice`·`putLatestPrice`
- `domain/market/port/KisRestPort.kt` — `fetchCandles`·`fetchPrice`·`fetchPriceMetrics`

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
- `frontend/src/pages/Symbol/SymbolPriceCard.tsx` — 현재가·등락 표시 (STOMP 단일 source)
- `frontend/src/pages/Symbol/SymbolChart.tsx` + `LightweightChart.tsx` — LWC v4 차트 (IntervalToggle 10종)
- `frontend/src/pages/Symbol/IntervalToggle.tsx` — 1m·5m·15m·30m·90m·1h·1d·1wk·1mo·1y 10종

### 6. PR

- PR #210 (T-S1 R1 SymbolController scaffold + CandleQueryService)
- PR #215 (T-S1 R2 Redis quote:last + STOMP tick forwarder)
- PR #289 (frontend STOMP tick sync — dogfooding fix)
- PR #291 (SymbolPriceCard 등락 STOMP 단일 source — hotfix)

### 7. ADR

- ADR 0029 — 당일 진행 중 캔들 Redis 캐시
- ADR 0048 — KIS WS 41 slot 한도
- ADR 0057 — KIS-only 데이터 출처
- ADR 0062 — 5m~1h 합산 가공
- ADR 0063 — NXT 통합 거래시간
- ADR 0064 — boundary fanout (v3.3.0 — 5m~1h 실시간 차트 갱신 STOMP)

### 8. 한계

- 호가 UI 분리 팝업 미구현 (현재 toast alert stub)
- 차트 그리기 도구(Draw) 미구현 (T-LWC W5)
- interval 2m 비활성 (KIS 미제공)

### 9. NXT 영향 (ADR 0063)

- `LightweightChart.tsx` `isIntradayInterval()` NXT 08:00~20:00 ts X축 자동 확장
- `AGGREGATED_INTERVALS` 5종(5m·15m·30m·90m·1h) 활성화 + 빈 응답 graceful
- v3.3.0: 5m~1h boundary closing STOMP 수신 시 LWC `series.update()` 실시간 갱신 (T-FANOUT PR #320)

---

## T-IND — Indicator (기술적 지표 80종)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `GET` | `/api/indicators` | 80지표 계산 (code·name·interval·params·from·to·adjusted) |

### 2. 도메인

- `domain/indicator/IndicatorEngine.kt` — 지표 엔진 인터페이스
- `domain/indicator/IndicatorCatalog.kt` — 80종 지표 카탈로그
- `domain/indicator/IndicatorSpec.kt` — `data class IndicatorSpec(name, params)`
- `domain/indicator/IndicatorResult.kt` — `data class IndicatorResult(spec, series, source)`
- `domain/indicator/IndicatorPoint.kt` — `data class IndicatorPoint(time: Instant, values)`

### 3. 영속

- DB 직접 영속 없음 — Redis 캐시만 (`indicator:cache:{code}:{interval}:{name}:{paramsHash}`)
- 분봉 TTL 300s / 일봉+ TTL 86400s

### 4. Kafka

없음 (요청 기반 동기 계산).

### 5. UI

- `frontend/src/pages/Symbol/IndicatorPanel.tsx` — 지표 파라미터 입력 + 시계열 시각화

### 6. PR

- PR #230 (T-IND R1 IndicatorController + native 5종)
- PR #240 (T-IND R2 KIS Strategy Builder 75종 + Redis 캐시)

### 7. ADR

- ADR 0021 — 지표 소스 추상화 (native vs KIS Strategy Builder dual source)
- ADR 0019 — 캔들 DB로부터 지표 입력

### 8. 한계

- consumer 모듈 실시간 tick 기반 지표 계산 미구현 (T-SIG 트랙에서 JvmStrategyEvaluator 내부 호출)
- adjusted=true 경로 DB에 openAdj·closeAdj 없으면 raw fallback

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

> v3.4.0 이후: T-SIG `StrategiesController` `/api/strategies`가 strategy 관리의 주 경로. `/api/strategy`는 T-DSL R1·R2 레거시 경로 보존.

### 2. 도메인

- `domain/strategy/Strategy.kt` — 전략 aggregate
- `domain/strategy/dsl/DslStrategyDefinition.kt` + 하위 AST 노드
- `domain/strategy/StrategyComplexity.kt` + `ComplexityClass.kt`
- `domain/strategy/Signal.kt` — `enum Signal { BUY, SELL, HOLD }`
- `domain/strategy/port/StrategyRepositoryPort.kt` — 포트 인터페이스
- `domain/backtest/*` — 백테스트 도메인 (BacktestRequest·Result·Trade·Position·Performance·Exception)

### 3. 영속

- `api/persistence/StrategyJpaEntity.kt` + `StrategyJpaRepository.kt`
- Flyway: V9 `strategy(id, user_id, name, entry_dsl_json, exit_dsl_json, complexity_json, disabled_reason, last_disabled_at)` + V14(v3.4.0 status·codes·intervals 확장)

### 4. Kafka

- `consumer/application/strategy/JvmStrategyEvaluator` — T-SIG에서 실시간 평가 sandbox 진입 (ADR 0047 정합)

### 5. UI

- `frontend/src/pages/Strategy/StrategyBuilderPage.tsx` — DSL 빌더 화면
- `frontend/src/pages/Strategy/DslEditor.tsx` — 코드 에디터

### 6. PR

- PR #220 (T-DSL R1 도메인 AST + StrategyController scaffold)
- PR #225 (T-DSL R2 KisYamlDslParser + DB 영속)

### 7. ADR

- ADR 0022 — KIS Strategy Builder 호환 DSL YAML superset
- ADR 0023 — 시그널 엔진 룰 온리 (AI 매매 결정 0)
- ADR 0047 — DSL 평가 sandbox (timeout 1000ms·누적 50회 abort)
- ADR 0055 — Strategy Builder 컴포넌트 스택

### 8. 한계

- `POST /api/strategy/{id}/evaluate` stub (JvmStrategyEvaluator — T-SIG에서 실시간 path 통합)
- 전략 import (`*.kis.yaml`) UI 업로드 미구현 (T-SB W2)
- 전략 공유·클론 기능 미구현

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

- PR #241 (T-PAT R1 PatternController + pattern-engine JvmPatternRecognizer 10종)
- PR #261 (T-PAT R2 57종 완성 + Redis 캐시)

### 7. ADR

- ADR 0054 — pattern-engine 모듈 분리
- ADR 0019 — 캔들 DB (PatternController 직접 사용 — ADR 0051 준수)

### 8. 한계

- 57종 중 R2 stub 47건 → `direction=NEUTRAL` → 결과 미포함 (graceful)
- 실시간 패턴 감지 (tick 기반) 미구현 (T-SIG W3)
- PatternOverlay 마커 클릭 상세 팝업 미구현

---

## T-BT — Backtest Engine (ADR 0056 + ADR 0061 — v3.3.1 마감)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/api/backtest/run` | DSL 전략 백테스트 stateless (ADR 0056 §5-1 보존) |
| `POST` | `/api/backtests` | 실행 + 영속 (PENDING→RUNNING→SUCCEEDED/FAILED) |
| `GET` | `/api/backtests` | 사용자 목록 (페이징 `?page=0&size=20`) |
| `GET` | `/api/backtests/{id}` | 단건 메타 + KPI |
| `GET` | `/api/backtests/{id}/result` | 거래·포지션 시계열 (페이징) |
| `DELETE` | `/api/backtests/{id}` | 삭제 (CASCADE — backtest_trade·backtest_position) |

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

- `api/persistence/BacktestRunJpaEntity.kt` + `BacktestRunJpaRepository.kt` — Flyway V12
- `api/persistence/BacktestTradeJpaEntity.kt` + `BacktestPositionJpaEntity.kt` — Flyway V13
- `BacktestPersister.kt` — @Transactional Success·Aborted 모두 영속
- `BacktestQueryService.kt` — 조회·삭제·소유권 게이팅

#### backtest_run 주요 컬럼 (ADR 0061 §2-2)

```
id, user_id, code, interval, rule_dsl, range_from, range_to,
status (PENDING/RUNNING/SUCCEEDED/FAILED),
started_at, completed_at,
total_return, win_rate, mdd, sharpe, sortino,
trade_count, position_count, detail_json (JSONB)
```

CASCADE DELETE: backtest_run 삭제 시 backtest_trade·backtest_position 일괄 삭제.

### 4. Kafka

없음.

### 5. UI

- `frontend/src/pages/Backtest/BacktestPage.tsx` — `/strategy/backtest` 라우트 (탭 네비·신규 실행·이력 뷰)
- `frontend/src/pages/Backtest/BacktestForm.tsx` — 전략 선택·종목·인터벌·기간·초기자본
- `frontend/src/pages/Backtest/BacktestList.tsx` — 목록 페이징·삭제·클릭→상세
- `frontend/src/pages/Backtest/BacktestDetail.tsx` — 단건 메타+KPI, PENDING/RUNNING 3초 폴링
- `frontend/src/pages/Backtest/BacktestKpiPanel.tsx` — 총수익률·승률·MDD·Sharpe·거래수 5 KPI 카드
- `frontend/src/pages/Backtest/EquityCurveChart.tsx` — LWC v4 line series — 포지션 누적 PnL 곡선
- `frontend/src/pages/Backtest/TradeTable.tsx` — 거래 페이징 테이블

### 6. PR

- PR #251 (T-BT R1 BacktestController + BacktestService + JvmBacktestEngine scaffold)
- PR #260 (T-BT R2 commission·slippage·tax 모델 + BacktestPerformanceCalculator)
- PR #331 (T-BT R2 결과 영속 — Flyway V12·V13 + JPA 3쌍 + REST 5 endpoint + ADR 0061)
- PR #332 (T-BT R3 frontend — /strategy/backtest + 6 컴포넌트 + LWC equity curve)

### 7. ADR

- ADR 0056 — 백테스트 엔진 아키텍처 (sandbox·LookaheadGuard·ADR 0047 정합)
- ADR 0061 — 백테스트 결과 영속 (ADR 0056 §2-6 supersede — v3.3.1 신규)
- ADR 0047 — DSL 평가 sandbox (timeout 1000ms·누적 50회 abort)
- ADR 0023 — 룰 기반 온리 (AI 호출 0)

### 8. 한계

- 다중 전략 동시 백테스트 미구현
- 백테스트 결과와 actual 시그널 비교 뷰 미구현 (T-J W3 트랙)
- 백테스트 통과 → 전략 활성화 게이트 자동 연결 미구현 (T-SIG 후속 — ADR 0061 §1 참조)

---

## T-FANOUT — 실시간 boundary fanout (v3.3.0 — ADR 0064)

### 1. REST

별도 REST 엔드포인트 없음.
- admin trigger: `POST /admin/aggregate/{code}` (port 8082) — AggregateAdminController

### 2. 도메인

- `domain/market/candle/BoundaryDetector.kt` — Pure Kotlin object (Spring 의존 0, ADR 0064 §4-1)
  - `isBoundaryClosed(ts, target)` — 5m·15m·30m·90m·1h NXT 08:00~20:00 cover
  - `boundaryStartTs(ts, target)` — boundary 시작 ts 계산
  - `parseHhmmssToInstant(hhmmss, midnight)` — KIS STCK_CNTG_HOUR raw 파싱
  - KIS-first raw 출처: H0STCNT0 `ccnl_krx()` `[1]` STCK_CNTG_HOUR — `examples_user/domestic_stock/domestic_stock_functions_ws.py`
- `domain/market/candle/BoundaryDetectorTest.kt` — 단위 테스트 44건

### 3. 영속

- `candle` 테이블 동일 스키마 (5m~1h INSERT ON CONFLICT DO NOTHING)
- `consumer/cache/RedisCandleCache.kt` — ADR 0064 A-1 5종 별도 Redis hash 키 (TTL 25h)

### 4. Kafka

| 토픽 | 방향 | 소비자 |
|------|------|-------|
| `market.candle.5m.closed.v1` | consumer→api+consumer | `CandleClosedStompListener`(api) + `BoundaryClosingKafkaListener`(consumer, v3.4.0) |
| `market.candle.15m.closed.v1` | consumer→api+consumer | 동상 |
| `market.candle.30m.closed.v1` | consumer→api+consumer | 동상 |
| `market.candle.90m.closed.v1` | consumer→api+consumer | 동상 |
| `market.candle.1h.closed.v1` | consumer→api+consumer | 동상 |

STOMP (ADR 0064 C-1): `/topic/market.candle.{interval}.{code}` 5종 별도 토픽

### 5. UI

- `frontend/src/hooks/useSymbolCandles.ts` — STOMP 5종 dynamic subscribe/unsubscribe
- `frontend/src/pages/Symbol/LightweightChart.tsx` — boundary 수신 시 `series.update()` 실시간 갱신
- `frontend/src/pages/Symbol/SymbolChart.tsx` — interval 5종 boundary 차트 갱신

### 6. PR

- PR #318 (architect plan — ADR 0064 Proposed → Accepted)
- PR #319 (ADR 0064 Accepted 갱신 + ADR 인덱스)
- PR #320 (market-data — BoundaryDetector + BoundaryAggregator + RedisCandleCache + CandleClosedStompListener + Kafka 5 토픽)
- PR #321 (frontend — useSymbolCandles STOMP 5종 + LWC series.update)

### 7. ADR

- ADR 0064 — Redis boundary fanout + STOMP 5종 (A-1·B-1·C-1 채택)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (§2-1·§2-5 LOCK)
- ADR 0062 — 5m~1h 합산 가공 (§4-3 boundary 트리거 권위)
- ADR 0063 — NXT 통합 거래시간 (boundary 시각 08:00~20:00 cover)
- ADR 0023 — Signal Engine 룰 온리 (5m·15m boundary 마감 T-SIG baseline)
- ADR 0019 — 캔들 저장 정책 (boundary INSERT)
- ADR 0051 — Spring Data Repository default fun 함정

### 8. 한계

- tick 0건 종목 boundary 누락 risk — WatchlistPollingWorker boundary 검사 미구현
- BoundaryAggregator in-process map 재기동 시 손실 (ON CONFLICT DO NOTHING 멱등으로 안전)
- 합산 결과 staleness: 1m UPDATE/DELETE 시 재계산 미구현

### 9. NXT 영향 (ADR 0063)

- boundary 정렬: 08:00~20:00 KST
- 5m: 144 bar/일, 15m: 48, 30m: 24, 90m: 8, 1h: 12
- `DailyCloseFinalizer` 20:00 KST flush — boundary fanout safety net 유지

---

## T-SIG — 실시간 시그널 엔진 (v3.4.0 신규 — ADR 0066)

> **AI 호출 0 의무** — 본 트랙 모든 컴포넌트 LLM provider import(Anthropic SDK·Spring AI ChatClient·OpenAI SDK) 0건 (ADR 0023 §2-4 + ADR 0066 §1 inline 주석 정책 LOCK).

### 1. REST

#### Strategy CRUD (6 endpoint — ADR 0066 §4-1)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `POST` | `/api/strategies` | 룰 등록 + ADR 0047 §2-1 static 검증 |
| `GET` | `/api/strategies` | 사용자 룰 목록 (페이징 `?page&size`) |
| `GET` | `/api/strategies/{id}` | 단건 + metric summary |
| `PUT` | `/api/strategies/{id}` | 전략 수정 (이름·DSL·codes·intervals) |
| `DELETE` | `/api/strategies/{id}` | soft delete (status=DELETED) |
| `PUT` | `/api/strategies/{id}/toggle` | status 토글 (ACTIVE↔PAUSED) / DISABLED→ACTIVE (reactivate) |

#### Signal 조회 (2 endpoint — ADR 0066 §4-2)

| 메서드 | 경로 | 기능 |
|--------|------|------|
| `GET` | `/api/signals` | 시계열 페이징 (`?userId&strategyId&code&from&to&page&size`) |
| `GET` | `/api/signals/{id}` | 단건 (side·strength·reason·price·contextJson) |

### 2. 도메인

#### Signal 본체 (ADR 0066 §5-2)

```kotlin
// domain/.../signal/Signal.kt
data class Signal(
    val id: Long,
    val strategyId: Long,
    val userId: String,
    val code: String,
    val interval: CandleInterval,
    val ts: Instant,                 // boundary closing ts (deterministic)
    val side: SignalSide,            // BUY · SELL · HOLD
    val strength: Int,               // 0~100 (ADR 0023 §2-1)
    val reason: String,
    val priceAtSignal: BigDecimal,
    val contextJson: String,
    val createdAt: Instant,
)

enum class SignalSide { BUY, SELL, HOLD }
```

#### Strategy 확장 (ADR 0066 §5-1 F-1)

```kotlin
enum class StrategyStatus { ACTIVE, PAUSED, DISABLED, DELETED }
```

#### 포트 인터페이스 (헥사고날 — ADR 0051 body 0 의무)

- `domain/.../signal/port/SignalRepository.kt` — `save(signal)`·`findByUser(...)`·`findById(id)`
- `domain/.../strategy/port/StrategyLookupPort.kt` — `findActiveByCodeAndInterval(code, interval)`
- `domain/.../signal/SignalDispatcher.kt` — STOMP fanout 포트
- `domain/.../signal/SignalGenerator.kt` — `generate(strategy, candles, context): Signal?`
- `domain/.../signal/SignalContext.kt` — `data class SignalContext(code, interval, boundaryTs, nowKst, indicatorCache)`

### 3. 영속

#### Flyway V14 — strategy 확장 (ADR 0066 §6-1)

```sql
ALTER TABLE strategy
    ADD COLUMN status       VARCHAR(10) NOT NULL DEFAULT 'ACTIVE'
        CONSTRAINT chk_strategy_status CHECK (status IN ('ACTIVE','PAUSED','DISABLED','DELETED')),
    ADD COLUMN codes        JSONB,      -- ["005930","000660"]
    ADD COLUMN intervals    JSONB,      -- ["5m","15m"]
    ADD COLUMN paused_at    TIMESTAMPTZ,
    ADD COLUMN disabled_at  TIMESTAMPTZ,
    ADD COLUMN deleted_at   TIMESTAMPTZ;

-- 부분 인덱스 — ACTIVE 전략 평가 hot path
CREATE INDEX idx_strategy_status_active ON strategy(status) WHERE status = 'ACTIVE';
```

기존 disabled_reason IS NOT NULL → status='DISABLED' 동기 업데이트 포함.

#### Flyway V15 — signal 신규 (ADR 0066 §6-2)

```sql
CREATE TABLE signal (
    id              BIGSERIAL PRIMARY KEY,
    strategy_id     BIGINT NOT NULL REFERENCES strategy(id) ON DELETE CASCADE,
    user_id         VARCHAR(40) NOT NULL,
    code            VARCHAR(8) NOT NULL,
    interval        VARCHAR(8) NOT NULL,
    ts              TIMESTAMPTZ NOT NULL,      -- boundary closing ts
    side            VARCHAR(4) NOT NULL CHECK(side IN ('BUY','SELL','HOLD')),
    strength        INTEGER NOT NULL DEFAULT 0,
    reason          VARCHAR(500),
    price           NUMERIC(19,4),
    rule_signal     VARCHAR(80),
    context_json    JSONB,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_signal_strategy_code_interval_ts
        UNIQUE (strategy_id, code, interval, ts)    -- E-1 멱등
);
```

index: `idx_signal_user_ts (user_id, ts DESC)` · `idx_signal_code_ts (code, ts DESC)` · `idx_signal_strategy (strategy_id, ts DESC)`

#### JPA 어댑터

- `api/persistence/signal/SignalJpaEntity.kt` + `SignalJpaRepository.kt` + `SignalJpaAdapter.kt`
  - DUPLICATE_KEY 예외: `log.debug("dup signal skipped")` + skip (E-1 DB UQ 정합)
  - `findByDedupeKey(strategyId, code, interval, ts)` — abstract fun (body 0 — ADR 0051)
  - `findByUserAndRange(userId, from, to, pageable)` — abstract fun (body 0)
- `api/persistence/strategy/StrategyJpaEntity.kt` 확장 — status·codes·intervals·paused_at·disabled_at·deleted_at 컬럼
- `api/persistence/strategy/StrategyJpaRepository.kt` 확장 — status 4단 쿼리·findActiveByCodeAndInterval native JSONB
  - 모든 query fun은 abstract (body 0 — ADR 0051 헌법 §6 LOCK)

### 4. Kafka

| 토픽 | 방향 | 발행자 | 소비자 |
|------|------|-------|-------|
| `market.candle.5m.closed.v1` | consumer→consumer | `BoundaryAggregator` | `BoundaryClosingKafkaListener` (groupId: `magicjar-consumer-signal-5m`) |
| `market.candle.15m.closed.v1` | consumer→consumer | `BoundaryAggregator` | `BoundaryClosingKafkaListener` (groupId: `magicjar-consumer-signal-15m`) |
| `market.candle.30m.closed.v1` | consumer→consumer | `BoundaryAggregator` | `BoundaryClosingKafkaListener` (groupId: `magicjar-consumer-signal-30m`) |
| `market.candle.90m.closed.v1` | consumer→consumer | `BoundaryAggregator` | `BoundaryClosingKafkaListener` (groupId: `magicjar-consumer-signal-90m`) |
| `market.candle.1h.closed.v1` | consumer→consumer | `BoundaryAggregator` | `BoundaryClosingKafkaListener` (groupId: `magicjar-consumer-signal-1h`) |
| `signal.generated.v1` | consumer→api | `SignalKafkaProducer` | `StompSignalDispatcher`(api) + `SignalJpaAdapter`(api) |

`signal.generated.v1`: partition key=userId · retention 7d · `KafkaTopicConfig.signalGeneratedTopic()` bean 추가.

STOMP (ADR 0066 D-1): `/topic/signal.user.{userId}` — userId 단위 격리 (ADR 0010 multi-tenant)

#### 시그널 평가 흐름 (consumer)

```
BoundaryClosingKafkaListener (5건)
    → RealtimeSignalGenerator.evaluate(code, interval, candle)
        ├── StrategyLookupPort.findActiveByCodeAndInterval(code, interval)
        │       → RedisStrategyCache (TTL 5분) → miss → DB
        ├── JvmStrategyEvaluator.evaluate(strategy, candles)   // ADR 0047 sandbox timeout 100ms
        │       HOLD → skip
        │       BUY/SELL → SignalDedupeGuard.check()
        │                   → Redis SETNX sigdedupe:{strategyId}:{code}:{interval}:{ts} 30s
        │                   → pass → SignalKafkaProducer.publish(signal.generated.v1)
        └── ObjectProvider<SignalRepository> graceful no-op (Phase 2 bean 없으면 skip)
```

#### 멱등 이중 가드 (ADR 0066 E-1)

- 1차: Redis SETNX `sigdedupe:{strategyId}:{code}:{interval}:{ts}` TTL 30s (fast-path, fail-open)
- 2차: DB UQ `(strategy_id, code, interval, ts)` → DUPLICATE_KEY → skip

### 5. UI

- `frontend/src/pages/Strategy/StrategyListPage.tsx` — `/strategy/list` 라우트
  - `StrategyCard.tsx` — 전략 카드 (status 배지·codes·intervals·토글 버튼)
  - `SignalListPanel.tsx` — 최근 시그널 목록 패널 (BUY=emerald/SELL=rose 배지)
  - `useStompSignal.ts` — `/topic/signal.user.{userId}` STOMP subscribe + 토스트 알림
  - `useStrategies.ts` — GET /api/strategies REST hook
  - `useSignals.ts` — GET /api/signals REST hook
- feedback_no_mock_data.md 정합 — strategy 0건·DISABLED·FAILED 명시 안내

### 6. PR

- PR #335 (architect plan — ADR 0066 Proposed → Accepted)
- PR #336 (strategy-engine — Phase 1: domain Signal + BoundaryClosingKafkaListener 5건 + SignalDedupeGuard + SignalKafkaProducer + signal.generated.v1 + 단위 테스트 6건)
- PR #337 (backend-core — Phase 2: Flyway V14·V15 + JPA 3쌍 + REST 8 endpoint + RedisStrategyCache + StompSignalDispatcher)
- PR #338 (frontend — Phase 3: /strategy/list + StrategyCard + SignalListPanel + useStompSignal 토스트)

### 7. ADR

- ADR 0066 — T-SIG 실시간 시그널 엔진 (A-1·B-1·C-1·D-1·E-1·F-1 채택 2026-05-12 — v3.4.0 신규)
- ADR 0023 — Signal Engine 룰 온리 (AI 호출 0 — 본 ADR의 W3 구현 권위)
- ADR 0047 — DSL Evaluation Sandbox (timeout 100ms 실시간·50회 abort·strategy-eval-pool)
- ADR 0064 — boundary fanout (시그널 트리거 진입점 — `market.candle.{interval}.closed.v1` 5종)
- ADR 0022 — KIS Strategy Builder 호환 DSL (룰 평가 권위)
- ADR 0061 — Backtest 결과 영속 패턴 (3 테이블 분리 + REST endpoint 패턴 baseline)
- ADR 0021 — Indicator Source 추상화 (평가 시 지표 호출)
- ADR 0007 — Kafka 이벤트 backbone (`signal.generated.v1` 신규 토픽)
- ADR 0029 — 당일 진행 중 캔들 Redis 캐시 (strategy cache 키 패턴 정합)
- ADR 0010 — multi-tenant (userId 단위 signal 격리)
- ADR 0051 — Spring Data Repository default fun 함정 (SignalJpaRepository·StrategyJpaRepository body 0)
- ADR 0045 — Graceful Shutdown (strategy-eval-pool SmartLifecycle)

### 8. 한계

- HOLD 시그널 영속 0건 (BUY/SELL only publish — HOLD는 평가 결과 skip)
- 시그널 → 주문 intent 자동 발행 미구현 (T-O 트랙 후행 — ADR 0042 Position Bootstrap 정합)
- Risk Guard chain 미연결 (T-RISK 후행 — ADR 0024 6단 chain)
- strategy_eval_metric V16 미구현 (ADR 0066 §6-3 — Flyway V16 후속 PR 의무)
- 전략 백테스트 통과 → 활성화 게이트 자동 연결 미구현 (ADR 0056 §3-4·ADR 0061 §1)
- signal row ≥ 5M 도달 시 monthly partition 미구현 (ADR 0066 §2-3 C-1 — 후속 ADR 0085+)
- D-2 Slack·이메일 다채널 알림 미구현 (T-NOTIFY 트랙 ADR 0044 합류 후)

### 9. NXT 영향 (ADR 0063)

- 시그널 평가 boundary: 08:00~20:00 KST (5m 144건/일, 15m 48건/일 등 NXT 전체)
- 5m·15m 인터벌이 가장 빈번한 평가 트리거 (주 활용 케이스)
- DailyCloseFinalizer 20:00 KST → strategy cache invalidateAll 연동 (당일 evict safety net)

---

## CandleAggregator — 합산 가공 도메인 (ADR 0062 — v3.3.0 이월)

### 1. REST

| 메서드 | 경로 | 설명 |
|--------|------|------|
| `POST` | `/admin/aggregate/{code}` (port 8082) | 특정 종목 수동 합산 트리거 |
| `POST` | `/admin/aggregate/all` (port 8082) | 전 watchlist 합산 트리거 |

### 2. 도메인

- `domain/market/candle/CandleAggregator.kt` — Pure Kotlin 합산 엔진 (Spring 의존 0)
  - 공식: `OHLCV = (first.open, max(highs), min(lows), last.close, sum(volumes))`
  - KIS raw 출처: FHKST03010230 `stck_oprc·stck_hgpr·stck_lwpr·stck_prpr·cntg_vol`

### 3. 영속

- `candle` 테이블 동일 스키마 (5m~1h ON CONFLICT DO NOTHING)

### 4. Kafka

- `BoundaryAggregator.onTickClosed` → `market.candle.{interval}.closed.v1` 5종 발행 (ADR 0064 B-1 trigger — v3.3.0 연결)
- v3.4.0: 5종 토픽 소비자에 `BoundaryClosingKafkaListener` 추가 (T-SIG Phase 1)

### 5. UI

- IntervalToggle 5종 활성화 (5m·15m·30m·90m·1h)
- STOMP 5종 topic subscribe → 실시간 `series.update()` 갱신 (T-FANOUT PR #321)

### 6. PR

- PR #298 (CandleAggregator 도메인 + AggregateBackfillRunner + ColdStartExecutor 확장)
- PR #302 (DailyCloseFinalizer 20:00 KST + CandleBootstrapRunner 5m 분기)
- PR #320 (v3.3.0 BoundaryAggregator + Kafka 5 토픽)

### 7. ADR

- ADR 0062 — 5m~1h 합산 가공 (Accepted 2026-05-11)
- ADR 0063 — NXT 통합 (§9 결합 영향)
- ADR 0064 — boundary fanout (v3.3.0 CandleAggregator + BoundaryAggregator 연결 권위)
- ADR 0019 — 캔들 저장 정책

### 8. 한계

- 합산 결과 staleness: 1m UPDATE/DELETE 시 재계산 미구현 (append-only 가정)

---

## AggregateBackfillRunner — cold-start 합산 후속 (ADR 0062 §4-5 — v3.3.0 이월)

### 1. REST

없음 (ColdStartExecutor·CandleBootstrapRunner에서 내부 호출).

### 2. 도메인

- `consumer/aggregation/AggregateBackfillRunner.kt` — 1m 전량 → CandleAggregator → 5m~1h INSERT
- `consumer/aggregation/AggregateAdminController.kt` (port 8082) — 수동 트리거

### 3. 영속

- `CandleRepository.findByCodeAndInterval(code, ONE_MIN)` 전량 조회 → `saveAll()` (ON CONFLICT DO NOTHING)

### 4. Kafka

없음.

### 5. UI

없음 (admin endpoint).

### 6. PR

- PR #298 (AggregateBackfillRunner 신규 + ColdStartExecutor 확장)

### 7. ADR

- ADR 0062 §4-5 — migration plan
- ADR 0051 — `CandleRepository.findByCodeAndInterval` abstract fun body 0

### 8. 한계

- backfill 진행 중 중복 실행 방지 분산 lock 미구현
- 종목 50+ 시 ColdStartExecutor thread pool 2개 한계 (backlog §28)

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
- `consumer/infrastructure/kis/KisRestCandleAdapter.kt` — 페이지 간 50ms delay + 3회 exponential backoff (PR #315)
- `consumer/infrastructure/kis/KisAuthAdapter.kt` — Redis 분산 lock (ADR 0038 — `kis:oauth:lock`)

---

## ADR 의존 요약 (트랙 전체 — v3.4.0 갱신)

| ADR | 영향 트랙 |
|-----|---------|
| ADR 0004 | 모든 트랙 (모듈 경계) |
| ADR 0007 | T-W1·T-S1·T-FANOUT·**T-SIG** (Kafka backbone — signal.generated.v1 포함) |
| ADR 0010 | **T-SIG** (multi-tenant userId 격리) |
| ADR 0019 | T-S1·T-IND·T-PAT·T-BT·CandleAggregator·T-FANOUT |
| ADR 0021 | T-IND·**T-SIG** (지표 소스 추상화 — 룰 평가 시 지표 호출) |
| ADR 0022 | T-DSL·**T-SIG** (KIS 호환 DSL — 룰 평가 권위) |
| ADR 0023 | T-DSL·T-BT·T-FANOUT·**T-SIG** (룰 온리 — AI 호출 0 본 ADR 구현 권위) |
| ADR 0028 | T-W1·T-S1 (Watchlist Ingestion Worker) |
| ADR 0029 | T-S1·CandleAggregator·T-FANOUT·**T-SIG** (Redis 캐시 — strategy cache 정합) |
| ADR 0037 | T-S1·CandleAggregator (KIS Rate Limit — T-SIG KIS REST 호출 0건) |
| ADR 0040 | T-W1 (Watchlist-first UI 흐름) |
| ADR 0045 | **T-SIG** (Graceful Shutdown — strategy-eval-pool SmartLifecycle) |
| ADR 0046 | 보안 (온보딩 키 등록) |
| ADR 0047 | T-DSL·T-BT·**T-SIG** (DSL sandbox — 실시간 timeout 100ms) |
| ADR 0048 | T-S1·T-FANOUT (KIS WS 41 slot) |
| ADR 0051 | 모든 JPA Repository (default fun 함정) |
| ADR 0054 | T-PAT (pattern-engine 모듈 분리) |
| ADR 0055 | T-DSL (Strategy Builder 컴포넌트 스택) |
| ADR 0056 | T-BT (백테스트 엔진 아키텍처 — stateless path 보존) |
| ADR 0057 | T-S1·CandleAggregator·**T-SIG** (KIS-only — 시그널 path KIS REST 직접 호출 0) |
| ADR 0061 | **T-BT** 영속 + **T-SIG** 패턴 참조 (3 테이블 분리 + REST endpoint 패턴) |
| ADR 0062 | CandleAggregator·AggregateBackfillRunner·T-FANOUT·**T-SIG** (boundary trigger) |
| ADR 0063 | T-S1·CandleAggregator·T-FANOUT·**T-SIG** (NXT 08:00~20:00 boundary) |
| ADR 0064 | T-FANOUT·**T-SIG** (boundary fanout + 5종 토픽 — 시그널 트리거 진입점) |
| ADR 0066 | **T-SIG** (실시간 시그널 엔진 — A-1·B-1·C-1·D-1·E-1·F-1 채택 — v3.4.0 신규 권위) |

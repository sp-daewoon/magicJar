# strategy-engine — Phase B-2 산출 리포트

브랜치: `agent/strategy-engine/indicators-dsl-backtest-journal`
대상 모듈: `:strategy` (Kotlin 2.0, JDK 21, Spring Boot 3.3, K2 컴파일러)
빌드 결과: `./gradlew :strategy:build` → **BUILD SUCCESSFUL**, `./gradlew :strategy:ktlintCheck` → **BUILD SUCCESSFUL**

## 1. 지표 라이브러리 (`strategy/indicator/`)

| 지표 | 파일 | 메모 |
|------|------|------|
| SMA | `Sma.kt` | 슬라이딩 윈도우, BigDecimal MathContext(12) |
| EMA | `Ema.kt` | alpha=2/(n+1), seed=SMA(n) |
| RSI(14) | `Rsi.kt` | Wilder smoothing |
| MACD(12,26,9) | `Macd.kt` | macd / signal / histogram 동시 산출 |
| Bollinger(20,2σ) | `BollingerBands.kt` | 모집단 분산 |
| Stochastic(%K14 %D3) | `Stochastic.kt` | rolling lowest/highest, %D = SMA(%K, d) |
| ATR(14) | `Atr.kt` | True Range + Wilder |
| ADX(14) | `Adx.kt` | +DI/-DI/DX → Wilder |
| CCI(20) | `Cci.kt` | TP, mean abs deviation, k=0.015 |
| Disparity(20) | `Disparity.kt` | 100 * close / SMA |
| VolumeOsc, AvgVolume | `VolumeOsc.kt` | PVO + SMA(volume) |
| 공통 | `IndicatorMath.kt` | MC=Decimal12, sqrt, divSafe |

**모든 값 BigDecimal**, 미정의 초기 구간은 null. `Double` 미사용.
픽스처: `strategy/src/test/resources/candles/005930-daily-sample.csv` (100봉).
유닛 테스트 9건(SMA·EMA·RSI·MACD·Bollinger·Stochastic·ATR·ADX/CCI/Disparity/Volume).

## 2. DSL 평가기 (`strategy/dsl/`)

- 재귀 하강 파서 (외부 의존 없음). 정식 문법은 `_workspace/strategy/dsl-grammar.md`.
- `Lexer.kt`, `Parser.kt`, `Ast.kt`, `Evaluator.kt` (cache, lookup), `IndicatorRegistry.kt` (지표 함수 카탈로그).
- 두 가지 모드:
  - `BarStrategyEvaluator` — `BarEvaluationContext(candles, currentIndex, extras)` 기반. 백테스트 엔진이 사용.
  - `DslStrategyEvaluator` — `core.port.StrategyEvaluator` 구현. flat `Map<String, BigDecimal>` 룩업.
- 미정의 값은 false로 단락 평가(이슈 없이 백테스트 진행).
- 6 유닛 테스트(Lexer/Parser 5 + Evaluator 4).

## 3. 백테스트 엔진 (`strategy/backtest/`)

- `BacktestEngine.run(BacktestRequest): BacktestResult`
  - universe × candles × DSL 평가 → 다음 봉 시가 체결 → equity curve 합산.
  - 비용 모델: `feeRate`, `taxRateKospi/Kosdaq`, `slippageBp`(BUY는 +, SELL은 -).
  - 결정론. 난수 사용 없음.
- `BacktestOrderSender` (@ConditionalOnProperty `magicjar.mode=BACKTEST`) → `core.port.OrderSender` 구현.
  - `BacktestExecutionContextHolder` ThreadLocal로 nextBarOpen·slippage·fillAt 주입.
  - 체결 시 `OrderFilledEvent` 발행.
- `MetricsCalculator` — totalReturn, CAGR(`(T/I)^(1/y)-1`), MDD(peak→drawdown), Sharpe(샘플 stddev × √252), winRate, tradeCount.
- 스모크 테스트 2건 (golden-cross, empty universe).

## 4. 매매일지 (`strategy/journal/`)

- `TradeJournalJpa` (`@Entity`, kotlin-jpa, JSONB jsonbCodec).
- `TradeJournalJpaRepository` (Spring Data JPA + JpaSpecificationExecutor).
- `TradeJournalRepositoryAdapter` → `core.port.TradeJournalRepository` 구현. `TradeJournalFilter`(mode, code, side, strategyId, from, to, tag, limit, offset)를 Specification으로 변환.
- `TradeJournalRecorder` — `@EventListener OrderFilledEvent`로 BUY → 진입 신규/평단 갱신, SELL → 청산 채움(pnl, pnlRate). `magicjar.journal.ai-review=true`이면 `AiReviewRequestedEvent` 발행.
- `IndicatorSnapshotProvider` — 주문 시점의 SMA20/SMA60/RSI14/MACD 스냅샷 맵 생성.
- `JournalJsonCodec` — Jackson Kotlin module 기반 JSONB ↔ Map/List 직렬화.
- 단위 테스트 1건 (recorder pnl/pnlRate 계산), Testcontainers IT 1건 (`@EnabledIfEnvironmentVariable INTEGRATION_TESTS=true`로 게이트, Docker 없을 때 skip).

## 5. Flyway

`strategy/src/main/resources/db/migration/V2__strategy_journal.sql`:
- `strategy(id PK, name, version, condition_expr text, created_at, updated_at)`
- `trade_journal(id UUID PK, trade_id UUID, mode, code, name, side, entry_at, entry_price numeric, entry_qty int, exit_at, exit_price numeric, exit_qty int, pnl numeric, pnl_rate numeric, strategy_id, entry_snapshot jsonb, exit_snapshot jsonb, entry_reason text, exit_reason text, ai_explanation text, ai_review text, tags jsonb, user_note text, created_at, updated_at, deleted_at)`
- 인덱스: `code`, `strategy_id`, `entry_at`, `mode`, `trade_id`.

## 6. app 통합

- `app/src/main/resources/application.yml` 에 `spring.config.import: [optional:classpath:application-strategy.yml]` 한 줄만 추가(다른 모듈도 같은 패턴으로 추가 가능).
- `strategy/src/main/resources/application-strategy.yml` — `magicjar.journal.ai-review: false` 기본 + 백테스트 비용 기본값 노출.
- `OrderSenderConfig` (backend-core 작성)는 그대로. `BacktestOrderSender`가 `@ConditionalOnProperty(magicjar.mode=BACKTEST)`로 등록되면 `@ConditionalOnMissingBean`인 placeholder가 자동 대체됨 — 수정 불필요.

## 7. 빌드 검증

```
./gradlew :strategy:build         # BUILD SUCCESSFUL
./gradlew :strategy:ktlintCheck   # BUILD SUCCESSFUL
./gradlew build -x test           # BUILD SUCCESSFUL (전체 모듈)
```

테스트 실행 결과: 단위 테스트 모두 통과. Testcontainers IT는 Docker 미구동 환경에서 자동 스킵.

## 8. 산출물 트리

- 메인: `strategy/src/main/kotlin/kr/spdaewoon/magicjar/strategy/{indicator,dsl,backtest,journal}/*.kt` (28 파일)
- 리소스: `strategy/src/main/resources/{application-strategy.yml, db/migration/V2__strategy_journal.sql}`
- 테스트: `strategy/src/test/kotlin/...` (15 파일) + 픽스처 CSV 1개
- 빌드 스크립트: `strategy/build.gradle.kts` (kotlin-jpa, spring-data-jpa, testcontainers, flyway 등 추가)
- 워크스페이스 문서: `_workspace/strategy/{phase-b-report.md, dsl-grammar.md, backtest-sample-result.md}`

## 9. 알려진 한계 / 후속

- DSL: 진입/청산 분리 미구현(현재 한 식만 사용해 단순 long-only). 후속 PR에서 `entryExpr`, `exitExpr`로 분리.
- BacktestOrderSender: ThreadLocal 컨텍스트 — 멀티 프로세스 백테스트는 별도 ExecutorService에서 set/clear 권장. 문서화 필요.
- Specification의 jsonb 태그 검색은 `LIKE '%"tag"%'` 단순 매칭 — 정밀 매칭은 `jsonb_array_elements` 함수로 향후 개선.
- `recordEntry/recordExit` 외부 호출 경로는 노출했으나, 현재 백테스트 엔진은 OrderFilledEvent를 통해서만 기록(스냅샷 미주입). Phase B-3에서 BacktestEngine → Recorder 직접 호출로 풍부한 컨텍스트 전달 예정.
- AI 리뷰 비동기 처리는 `ai` 모듈에서 `AiReviewRequestedEvent` 핸들러를 구현해야 완성.

## 10. 협업 인터페이스

- **market-data**: `CandleRepository.find(code, interval, from, to)` 호출만 사용. Instant 시간키, KST 변환은 백테스트 엔진 내부에서 처리.
- **ai-engineer**: 자연어 → DSL 매핑 시 `dsl-grammar.md`의 함수 카탈로그/컬럼 참조를 사양으로 사용. `AiReviewRequestedEvent`(journalEntryId UUID) 핸들러 구현 시 `TradeJournalRepository.find(id)`로 엔트리 조회.
- **backend-core**: `OrderSender`, `StrategyEvaluator`, `TradeJournalRepository` 포트 합치 확인. 추가 변경 없음.
- **frontend**: `TradeJournalRepositoryAdapter.search(filter)` 그대로 노출(REST 컨트롤러는 web 모듈에서). 백테스트 결과 JSON 스키마는 `BacktestResult` data class를 그대로 직렬화.

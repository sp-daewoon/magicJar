# strategy 모듈 — Phase B-2 pre-QA 작업 요약

**시점:** 2026-04-25 17:20 KST · **상태:** Phase C(QA) 검수 직전
**브랜치:** `agent/strategy-engine/indicators-dsl-backtest-journal` · **PR:** #7 (base=qa)
**에이전트:** strategy-engine · **스킬:** magicjar-backtest-engine, magicjar-trading-journal

## 무엇을 작업했나

| 영역 | 산출물 |
|------|-------|
| 기술적 지표 (11) | `Sma` · `Ema`(Wilder) · `Rsi`(14) · `Macd`(12,26,9) · `BollingerBands`(20,2σ) · `Stochastic`(%K14 %D3) · `Atr` · `Adx` · `Cci` · `Disparity`(이격도) · `VolumeOsc` + 공용 `IndicatorMath` |
| 조건식 DSL | 재귀 하강 lexer + parser (외부 의존 0, ANTLR 미사용) · `DslStrategyEvaluator` (`core.port.StrategyEvaluator` 구현) · `EvaluationContext` data class |
| 백테스트 엔진 | `BacktestEngine`(universe × 기간 × interval 순회 + 다음 봉 시가 체결 + 수수료/세금/슬리피지) · `BacktestOrderSender`(`OrderSender` 구현, `OrderFilledEvent` 발행) · `MetricsCalculator`(totalReturn/CAGR/MDD/Sharpe(252)/winRate/tradeCount) |
| 매매일지 | `TradeJournalJpa`(kotlin-jpa) · `TradeJournalJpaRepository` · `TradeJournalRepositoryAdapter`(`core.port.TradeJournalRepository` 구현) · `TradeJournalRecorder`(`@EventListener OrderFilledEvent`, 진입/청산 자동 기록) · `IndicatorSnapshotProvider`(주문 시점 SMA/RSI/MACD 등 `Map<String, BigDecimal>`) · `JournalJsonCodec` |
| Flyway | `V2__strategy_journal.sql` (strategy, trade_journal + 인덱스 code/strategy_id/entry_at) |
| 테스트 (15) | 11 indicator unit + LexerParser + Evaluator + MetricsCalculator + BacktestEngineSmoke + TradeJournalRecorderUnit + TradeJournalIT(Testcontainers Postgres) |
| 픽스처 | `005930-daily-sample.csv` (가상 100봉) |
| 설정 | `application-strategy.yml` (`magicjar.journal.ai-review` 플래그) |

## 빌드 상태
- `./gradlew :strategy:build` → **BUILD SUCCESSFUL**
- `./gradlew ktlintCheck` → **BUILD SUCCESSFUL**

## 의존 / 경계
- core 포트만 의존: `OrderSender`, `StrategyEvaluator`, `TradeJournalRepository`, `OrderFilledEvent`
- market-data, ai 등 다른 어댑터 모듈 import 없음

## QA 체크 포인트
- `OrderSenderConfig`(backend-core 작성) placeholder가 `BacktestOrderSender`로 자동 대체되는지 (`@ConditionalOnMissingBean`)
- DSL 문법 정확성 — `_workspace/strategy/dsl-grammar.md` 참조
- 골든크로스 스모크 백테스트 결과 — `_workspace/strategy/backtest-sample-result.md` 메트릭 합리성
- `TradeJournalRecorder`가 OrderFilledEvent 진입/청산을 정확히 매칭(같은 trade_id로 묶임)
- pnl 계산 정확성 (수수료·세금·슬리피지 차감 후)

## 알려진 TODO (후속)
- Testcontainers IT는 Docker 부재 시 자동 skip (env `INTEGRATION_TESTS=true` 필요)
- 진입/청산 식 분리 (현재 단일 expr로 신호 → 추후 양방향)
- 마지막 봉 강제 청산 옵션
- 분봉 백테스트 시 시간 가중 슬리피지 모델

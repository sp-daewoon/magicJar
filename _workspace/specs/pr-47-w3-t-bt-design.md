# PR-47 W3 T-BT Backtest Engine 강화 spec

> **상태**: spec LOCK (architect 발행 — PR-47)
> **작성일**: 2026-05-11
> **작성자**: architect (opus 4.7 1M)
> **연관 ADR**: 0001(룰 본체) · 0022(DSL .kis.yaml superset) · 0023(SignalEngine 룰 only) · 0047(DSL Evaluation Sandbox §2-2 backtest 1000ms · §2-5 백테스트 abort) · 0050(CHANGELOG 슬롯) · 0055(Strategy Builder UI — `EvaluationResultCard` 연계) · 0056 신규(Backtest Engine Architecture)
> **선행 트랙 의존**: T-W1·T-W2·T-S1(완료) · T-IM1·T-CA1(완료) · T-IND·T-PAT·T-DSL·T-SB(W2 완료)
> **본 PR 범위**: 새 트랙 T-BT 진입 spec — backend-core 메인 + (optional) strategy-engine 보조 + (optional) frontend `EvaluationResultCard` 확장.

---

## §1. 컨텍스트

W3 진입 시점(2026-05-11). W1·W2 트랙 머지 완료 — instrument master·corp_action·watchlist ingestion·indicator 80지표·pattern 57패턴·DSL parser+evaluator+sandbox·Strategy Builder UI 9 트랙 9 PR 묶음.

W3 첫 트랙으로 **T-BT(Backtest Engine)** 진입. 마스터 §B.9 권위 의무 인용:

> **B.9 Backtest Engine** — DSL → 과거 시계열 simulation → 매매 기록 + 성과 지표. **룩어헤드 차단·생존자 편향 보정·세금/수수료/슬리피지 모델**. 오프라인 동작 — 영속 candle만 있으면.

T-BT는 v3.2.0 베이스라인 시점에서 **신규 트랙** — `find . -name "*Backtest*"` 0건 검증 GREEN. v1/v2 잔재 0건. **자동매매 본체 fitness 정합** — 사용자가 작성한 DSL 전략을 실전 적용 전에 과거 데이터로 시뮬레이션해 확신 획득.

ADR 0047 §2-2 sandbox 정합 의무 — 백테스트 평가는 **strategy-eval-pool 경유 + timeout 1000ms** LOCK (이미 코드상 `JvmStrategyEvaluator.evaluateBacktest()` 가 backtest 모드 지원).

### 1-1. 선행 트랙 의존 코드 경로 (사실 인용)

- **T-CA1 (corp_action) 권위 코드**:
  - `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/persistence/CorpActionJpaRepository.kt`
  - `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/persistence/CorpActionJpaEntity.kt`
  - `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/persistence/CorpActionRepositoryAdapter.kt`
  - `batch/src/main/kotlin/kr/spdaewoon/magicjar/batch/job/corp_action/`

- **T-DSL (consumer) 권위 코드**:
  - `consumer/src/main/kotlin/kr/spdaewoon/magicjar/consumer/application/strategy/JvmStrategyEvaluator.kt`
    - `evaluateBacktest(strategyId, ast, candles)` — timeout 1000ms · auto-disable=false LOCK 보존
  - `consumer/.../strategy/KisYamlDslParser.kt`
  - `consumer/.../strategy/StrategyEvalPoolConfig.kt` (fixed=4, queue=100, CallerRunsPolicy)
  - `consumer/.../strategy/StrategyEvalMetricRecorder.kt` (ring buffer N=100)

- **T-DSL (api) 권위 코드**:
  - `api/src/main/kotlin/kr/spdaewoon/magicjar/api/controller/StrategyController.kt` (Swagger 4단 패턴)

### 1-2. fitness gap 사전 검증

- **v1/v2 잔재 검증**: orchestrator `find . -name "*Backtest*"` 0건 GREEN. T-BT는 v3 신규 트랙.
- **자동매매 적합성**: 본체 흐름의 **확신 게이트** — 사용자가 매매 활성화 전 반드시 백테스트 통과 의무 (사용자 운영 정책 — 룰 5 정합).
- **AI 매매 결정 0건**: 백테스트는 룰 기반 시뮬레이션만 (룰 5 LLM import 0건 의무).

---

## §2. 마스터 §B.9 정독 + 인용

```
B.9 Backtest Engine
  책임: DSL → 과거 시계열 simulation → 매매 기록 + 성과 지표
  필수 요소: 룩어헤드 차단 · 생존자 편향 보정 · 세금/수수료/슬리피지 모델
  오프라인 동작: 가능 (영속 candle만 있으면)
  트랙: T-BT
```

또한 §3-5 데이터 정합성 원칙:

> **Look-ahead 차단** — 백테스트는 t 시점에서 t 시점까지의 데이터만 보장. corp_action은 ex-date 기준
> **생존자 편향 보정** — universe는 시점별 — 2025-01-01 backtest는 2025-01-01 시점 상장 종목만
> **수정 주가** — corp_action 발생 시 candle.close_raw·close_adjusted 양 컬럼. 차트·백테스트 default는 adjusted
> **세금·수수료·슬리피지** — 거래세 0.18% · 수수료 0.015% · VAT · 슬리피지 0.05% default. DSL에서 파라미터화

본 spec은 위 5건을 코드 차원에서 의무 LOCK한다.

---

## §3. 강화 영역 6건 (DoD 분류)

### 3-1. 룩어헤드 가드 (Look-ahead bias prevention)

- BacktestEngine은 **slot-by-slot** 시간 진행 — 시점 t에서 평가 시 `candles[0..t]` 슬라이스만 evaluator에 전달
- DSL evaluator에 `candles[t+1..]` 데이터 전달 절대 금지 — 컴파일/런타임 양차원 가드
- **Runtime 검증**: BacktestEngine 외부에서 evaluator 직접 호출 시 wrapper 강제. 단위 테스트 5건 — "t=10에서 t+1 캔들 접근 시도 → IllegalStateException" 등 negative case 의무
- **세부 정책**: corp_action 적용은 ex-date 시점 기준 — t < ex-date 시점에는 미반영 (이미 V14 candle 테이블 raw + adjusted 양 컬럼 정합)

### 3-2. 세금 모델

기본값(KRX·국세청 raw 출처 인용 의무):
- **증권거래세 0.18%** (KOSPI·KOSDAQ 매도 시) — 출처: 국세청 https://www.nts.go.kr — **architect는 본 spec 단계에서 raw fetch 발동 0, sub-agent backend-core가 코드 발행 시 raw 출처 인용 의무**
- **농어촌특별세 0.15%** (KOSPI 매도 시 한정)
- **양도세** 1차 모델 0% (개인 비대주주 가정 — 후속 ADR `0080+`에서 대주주 분기 검토)

도메인 모델: `BacktestTaxModel` interface (open extension). default 구현 `KoreanStockTaxModel`. DSL `tax_model: 'korean_default'` 토큰 추가 (DSL 화이트리스트 확장 — ADR 0047 §2-1-A 정합).

### 3-3. 수수료 모델

기본값(KIS 매뉴얼 raw 출처 인용 의무):
- **위탁 수수료 0.015%** (KIS 다이렉트 기준) — 출처: KIS https://securities.koreainvestment.com
- **유관기관 수수료 0.0036%** (KRX·예탁원·증선위 합산)
- **VAT 10%** (수수료 위에 부과)

도메인 모델: `BacktestCommissionModel`. default `KisDirectCommissionModel`. DSL `commission_model: 'kis_direct'` 토큰.

### 3-4. 슬리피지 모델

magicJar own (KIS raw 부재 — 자체 결정 명시):
- **고정 슬리피지 0.05%** — 매수 시 +0.05%, 매도 시 -0.05%
- **거래량 기반 슬리피지** (post-MVP) — 주문 수량이 분봉 거래량의 N% 초과 시 비례 증가

도메인 모델: `BacktestSlippageModel`. default `FixedRateSlippageModel(rate=0.0005)`. DSL `slippage_model: 'fixed_0.05'` 토큰.

### 3-5. DSL Sandbox 통합 (ADR 0047 정합)

BacktestEngine은 **반드시** `JvmStrategyEvaluator.evaluateBacktest()` 경유:
- timeout 1000ms (ADR 0047 §2-2-A)
- auto-disable=false (백테스트는 timeout 발생해도 strategy를 disable하지 않음 — 단 abort)
- abort 정책 (ADR 0047 §2-5): 한 백테스트 내 timeout 누적 ≥ 50회 시 백테스트 abort + 사유 반환

직접 `walkRoot()` 호출 절대 금지 — 모든 평가는 strategy-eval-pool 경유.

### 3-6. timeout 1000ms · abort

- 단일 평가 timeout 1000ms = ADR 0047 §2-2-A backtest 모드
- 누적 timeout ≥ 50회 시 BacktestEngine.run() 결과는 `BacktestResult.Aborted(reason="TIMEOUT_QUOTA_EXCEEDED")` 반환
- abort 시 부분 매매 기록(trades)은 보존 + 사유 표시

---

## §4. 도메인 모델 동결 (LOCK)

```kotlin
package kr.spdaewoon.magicjar.domain.backtest

import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDate
import java.math.BigDecimal

// =====================================================================
// 요청 (BacktestRequest)
// =====================================================================

data class BacktestRequest(
    val strategyId: Long,
    val universe: List<String>,         // 종목코드 list (생존자 편향 보정은 universe resolver가 별도)
    val interval: String,               // "1d" · "1m" · "5m" · "15m" · "1h"
    val from: LocalDate,                // 시작 (포함)
    val to: LocalDate,                  // 종료 (포함)
    val initialCapital: BigDecimal,     // 초기 자본 (KRW)
    val taxModel: String = "korean_default",
    val commissionModel: String = "kis_direct",
    val slippageModel: String = "fixed_0.05",
    val priceAdjustmentMode: PriceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
    val sizingPolicy: SizingPolicy = SizingPolicy.AllCapital,  // 1차: 매수 시 전액 투입
) {
    init {
        require(from <= to) { "from must be <= to" }
        require(initialCapital > BigDecimal.ZERO) { "initialCapital must be positive" }
        require(universe.isNotEmpty()) { "universe must be non-empty" }
    }
}

enum class PriceAdjustmentMode { RAW, ADJUSTED }

sealed class SizingPolicy {
    object AllCapital : SizingPolicy()                          // 1차 default
    data class FixedFractional(val fraction: BigDecimal) : SizingPolicy()  // post-MVP
    data class Kelly(val winRate: BigDecimal, val payoff: BigDecimal) : SizingPolicy()  // post-MVP
}

// =====================================================================
// 결과 (BacktestResult)
// =====================================================================

sealed class BacktestResult {
    data class Success(
        val request: BacktestRequest,
        val trades: List<BacktestTrade>,
        val positions: List<BacktestPosition>,
        val finalCapital: BigDecimal,
        val performance: BacktestPerformance,
        val executedAt: Instant,
        val durationMs: Long,
    ) : BacktestResult()

    data class Aborted(
        val request: BacktestRequest,
        val partialTrades: List<BacktestTrade>,
        val reason: String,                                       // "TIMEOUT_QUOTA_EXCEEDED" · "EVALUATION_ERROR" · ...
        val timeoutCount: Int,
        val executedAt: Instant,
        val durationMs: Long,
    ) : BacktestResult()
}

data class BacktestPosition(
    val code: String,
    val quantity: Long,                                            // 보유 수량 (음수=공매도 — 1차 미지원)
    val avgEntryPrice: BigDecimal,                                 // 평균 매수가
    val openedAt: Instant,
    val closedAt: Instant?,                                        // null=현재 보유 중
    val realizedPnl: BigDecimal,                                   // 실현 손익 (close된 경우만)
)

data class BacktestTrade(
    val code: String,
    val side: TradeSide,                                           // BUY · SELL
    val quantity: Long,
    val executedPrice: BigDecimal,                                 // 슬리피지 적용 후 가격
    val rawPrice: BigDecimal,                                      // 슬리피지 적용 전 (candle close)
    val executedAt: Instant,
    val commission: BigDecimal,
    val tax: BigDecimal,
    val slippage: BigDecimal,
    val signalReason: Map<String, Any>,                            // DSL evaluator의 reason 맵
)

enum class TradeSide { BUY, SELL }

data class BacktestPerformance(
    val totalReturn: BigDecimal,                                   // (finalCapital - initialCapital) / initialCapital
    val cagr: BigDecimal,                                          // 연환산 수익률 (Compound Annual Growth Rate)
    val maxDrawdown: BigDecimal,                                   // MDD (max drawdown) %
    val sharpeRatio: BigDecimal?,                                  // null = 일봉 < 30개 등 부족
    val winRate: BigDecimal,                                       // 승률 = 이익 거래 / 총 거래
    val totalTrades: Int,
    val winningTrades: Int,
    val losingTrades: Int,
    val avgWin: BigDecimal,                                        // 평균 이익 금액
    val avgLoss: BigDecimal,                                       // 평균 손실 금액 (양수)
    val profitFactor: BigDecimal?,                                 // sumWin / sumLoss
    val tradingFrequency: BigDecimal,                              // 매매 빈도 = 총 거래 / 영업일수
)

// =====================================================================
// 모델 인터페이스 (open extension)
// =====================================================================

interface BacktestTaxModel {
    val name: String
    fun calculate(side: TradeSide, price: BigDecimal, quantity: Long, market: String): BigDecimal
}

interface BacktestCommissionModel {
    val name: String
    fun calculate(side: TradeSide, notional: BigDecimal): BigDecimal
}

interface BacktestSlippageModel {
    val name: String
    fun apply(side: TradeSide, rawPrice: BigDecimal): BigDecimal
}

interface BacktestEngine {
    fun run(request: BacktestRequest): BacktestResult
}
```

**LOCK 사항**:
- `BacktestRequest`·`BacktestResult` (sealed)·`BacktestPosition`·`BacktestTrade`·`BacktestPerformance` 5개 도메인 data/sealed class — 본 PR-47 머지 시점 동결
- `BacktestEngine` port — `domain/backtest/port/BacktestEngine.kt`
- 모델 인터페이스 3종 (Tax · Commission · Slippage) — `domain/backtest/port/`
- 도메인 모듈 의존 0 (헥사고날 정합) — Spring·JPA·Kafka import 절대 금지

---

## §5. REST endpoint 동결

### 5-1. POST /api/backtest/run

```kotlin
@Tag(name = "Backtest", description = "백테스트 — DSL 전략을 과거 데이터로 시뮬레이션 · W3 T-BT")
@RestController
@RequestMapping("/api/backtest")
class BacktestController(
    private val backtestService: BacktestService,
) {
    @Operation(
        summary = "백테스트 실행",
        description =
            "DSL 전략을 과거 candle 데이터로 시뮬레이션하여 매매 기록 + 성과 지표를 반환합니다. " +
                "ADR 0047 §2-5 — 평가 timeout 1000ms · 누적 50회 초과 시 abort. " +
                "ADR 0023 정합 — 룰 매칭 only, AI 호출 0건.",
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "백테스트 성공 — 매매 기록 + 성과 지표"),
        ApiResponse(responseCode = "206", description = "백테스트 abort — 부분 결과 + 사유"),
        ApiResponse(
            responseCode = "400",
            description = "잘못된 요청 — strategyId 미존재 / 기간 역전 / universe 0건",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
        ApiResponse(
            responseCode = "503",
            description = "캔들 데이터 부족 — backfill 미완료",
            content = [Content(schema = Schema(implementation = ErrorResponse::class))],
        ),
    )
    @PostMapping("/run")
    fun run(@RequestBody req: BacktestRunRequest): BacktestRunResponse {
        // …
    }
}

@Schema(description = "백테스트 실행 요청")
data class BacktestRunRequest(
    @field:Schema(description = "전략 ID", example = "12", required = true)
    val strategyId: Long,
    @field:Schema(description = "유니버스 종목코드 리스트", example = "[\"005930\",\"000660\"]", required = true)
    val universe: List<String>,
    @field:Schema(description = "캔들 인터벌", example = "1d")
    val interval: String = "1d",
    @field:Schema(description = "시작일 (포함)", example = "2024-01-01", required = true)
    val from: String,
    @field:Schema(description = "종료일 (포함)", example = "2025-01-01", required = true)
    val to: String,
    @field:Schema(description = "초기 자본 (KRW)", example = "10000000", required = true)
    val initialCapital: BigDecimal,
    @field:Schema(description = "세금 모델", example = "korean_default")
    val taxModel: String = "korean_default",
    @field:Schema(description = "수수료 모델", example = "kis_direct")
    val commissionModel: String = "kis_direct",
    @field:Schema(description = "슬리피지 모델", example = "fixed_0.05")
    val slippageModel: String = "fixed_0.05",
    @field:Schema(description = "가격 모드 (RAW · ADJUSTED)", example = "ADJUSTED")
    val priceAdjustmentMode: String = "ADJUSTED",
)

@Schema(description = "백테스트 실행 응답")
data class BacktestRunResponse(
    @field:Schema(description = "결과 상태 (SUCCESS · ABORTED)", example = "SUCCESS")
    val status: String,
    @field:Schema(description = "매매 기록")
    val trades: List<BacktestTradeDto>,
    @field:Schema(description = "포지션 (현재 보유 + 종료)")
    val positions: List<BacktestPositionDto>,
    @field:Schema(description = "최종 자본 (KRW)")
    val finalCapital: BigDecimal,
    @field:Schema(description = "성과 지표")
    val performance: BacktestPerformanceDto,
    @field:Schema(description = "abort 사유 (status=ABORTED 시)")
    val abortReason: String? = null,
    @field:Schema(description = "실행 소요 시간 ms")
    val durationMs: Long,
)
```

**LOCK**: Swagger 4단 어노테이션 (사용자 메모리 `feedback_swagger_pattern.md` 정합 의무) — `@Tag` · `@Operation` · `@Parameter`/`@RequestBody` · DTO `@Schema` 필드. 한국어 description + example 값 의무.

### 5-2. GET /api/backtest/{id} (선택 — post-MVP)

백테스트 결과 영속 + 조회 endpoint. 1차 PR-47에서는 stateless (run 시 즉시 응답). 후속 PR에서 결과 DB 영속 + 조회 endpoint 추가 검토 (backlog 합류).

---

## §6. 데이터 흐름

```
[사용자] frontend → POST /api/backtest/run (BacktestRunRequest)
   ↓
[api] BacktestController.run() → BacktestService.runBacktest()
   ↓
[api] BacktestService:
   1. strategyId로 Strategy 조회 (StrategyRepository)
   2. universe × from~to range로 candle 영속 조회 (CandleRepository)
   3. corp_action 조회 + adjusted 가격 매핑 (CorpActionRepository — T-CA1 권위)
   4. BacktestEngine.run(BacktestRequest) 호출
   ↓
[api] BacktestEngine (interface 구현 — JvmBacktestEngine):
   - 시간 슬라이스 진행 (t = from … to)
   - 각 t에서 universe 순회:
     - candles[0..t] 슬라이스 (룩어헤드 차단)
     - JvmStrategyEvaluator.evaluateBacktest(strategyId, ast, slice) ← strategy-eval-pool 경유, timeout 1000ms
     - signal == BUY · 미보유 시 → 매수 시뮬 (수수료 · 슬리피지 적용)
     - signal == SELL · 보유 시 → 매도 시뮬 (수수료 · 세금 · 슬리피지 적용)
     - timeout 발생 시 timeoutCount++; if (timeoutCount >= 50) abort
   - 종료 시 BacktestPerformance 계산
   ↓
[api] 응답 BacktestRunResponse (status: SUCCESS · ABORTED)
   ↓
[frontend] EvaluationResultCard 확장 — 백테스트 결과 카드 표시
```

**핵심 LOCK**:
- BacktestEngine은 **반드시 strategy-eval-pool 경유** — 직접 evaluator 호출 절대 금지 (ADR 0047 §2-3-B 정합)
- candle 조회 시점 = 시뮬 시작 1회 (전체 range) — 슬라이스만 시간 진행. 매 t마다 DB 조회 절대 금지 (성능 보호)
- corp_action 적용은 candle 조회 직후 1회 — adjusted_price 컬럼이 candle 테이블에 이미 있음 (T-CA1 정합)

---

## §7. 성과 계산식

### 7-1. 총수익 (Total Return)
```
totalReturn = (finalCapital - initialCapital) / initialCapital
```

### 7-2. 연환산 수익률 (CAGR — Compound Annual Growth Rate)
```
years = (to - from) days / 365.25
cagr = (finalCapital / initialCapital) ^ (1/years) - 1
```

### 7-3. 최대 낙폭 (MDD — Maximum Drawdown)
```
equity_curve = [initialCapital, ..., finalCapital]  // 매 거래 후 자본 시계열
peak_at_t = max(equity_curve[0..t])
drawdown_at_t = (peak_at_t - equity_curve[t]) / peak_at_t
maxDrawdown = max(drawdown_at_t for all t)
```

### 7-4. Sharpe Ratio
```
daily_returns = [r1, r2, ..., rn]  // 일별 수익률
mean = mean(daily_returns)
stdev = stdev(daily_returns)
risk_free_rate = 0.025 / 252  // 연 2.5% 무위험 (한국 국채 3년물 기준) → 일별
sharpe = (mean - risk_free_rate) / stdev * sqrt(252)  // 연환산
```

n < 30 시 `null` 반환 (통계적 유의성 부족).

### 7-5. 승률 (Win Rate)
```
closed_trades = trades.filter { it.side == SELL && pair에 BUY 매칭 }
winning_trades = closed_trades.filter { realizedPnl > 0 }
winRate = winning_trades.size / closed_trades.size
```

### 7-6. Profit Factor
```
sumWin = sum(realizedPnl) where realizedPnl > 0
sumLoss = sum(abs(realizedPnl)) where realizedPnl < 0
profitFactor = sumWin / sumLoss  // sumLoss == 0 시 null
```

### 7-7. 매매 빈도 (Trading Frequency)
```
businessDays = KRX 영업일 수 (from~to)
tradingFrequency = trades.size / businessDays
```

---

## §8. KIS-first 의무 (raw 출처 인용)

### 8-1. 세금 모델 raw 출처

| 항목 | 출처 | URL |
|---|---|---|
| 증권거래세 0.18% | 국세청·KRX | https://www.krx.co.kr (거래수수료·세금 안내) |
| 농어촌특별세 0.15% (KOSPI) | 국세청 | https://www.nts.go.kr |
| VAT 10% | 국세청 부가가치세법 | https://www.nts.go.kr |

**LOCK**: backend-core sub-agent가 PR-48 코드 발행 시 `KoreanStockTaxModel` KDoc에 위 raw 출처 의무 인용 (자체 추론 0).

### 8-2. 수수료 모델 raw 출처

| 항목 | 출처 |
|---|---|
| KIS 다이렉트 위탁 수수료 | https://securities.koreainvestment.com (수수료 안내) |
| 유관기관 수수료 | KRX·KSD·증선위 |

### 8-3. 슬리피지 모델 — magicJar own

KIS·KRX raw에 슬리피지 표준 부재. magicJar own 결정:
- default 0.05% — 매수·매도 대칭
- **사유**: 일반적 한국 주식 시장 분봉 평균 spread + 1tick(0.1~0.5%)의 보수적 절반
- 후속 ADR `0080+`에서 거래량 기반 동적 모델 검토

### 8-4. architect 본 단계 raw fetch 발동 정책

본 spec 발행 단계(PR-47 architect)에서는 raw fetch 발동 0 — design.md에 출처 인덱스만 명시. 실제 raw fetch는 sub-agent backend-core(PR-48) 코드 발행 시 의무 (헌법 §3 KIS-first).

---

## §9. Frontend 영향

### 9-1. PR-50 frontend (선택)

- `EvaluationResultCard.tsx` (PR-45 T-SB 신설) 확장 — 백테스트 모드 표시 분기 추가
- `useStrategyEvaluate` hook — 백테스트용 별도 hook `useBacktestRun` 신설 (TanStack Query mutation)
- 새 페이지 또는 모달 검토 — Strategy Builder 페이지 내 "백테스트 실행" 버튼 클릭 시 모달 표시 + 결과 시각화 (총수익·CAGR·MDD·Sharpe·승률·매매빈도 카드 + trades 표 + 자본 곡선 LWC 차트)

### 9-2. 1차 범위 LOCK

PR-50 frontend는 **선택** — 본 PR-47 사이클에서 backend-core PR-48가 핵심. frontend는 backlog 합류 후 별도 사이클로 분리해도 무방.

---

## §10. fitness gap (v1/v2 잔재 0건 검증)

| 검증 항목 | 결과 |
|---|---|
| `find . -name "*Backtest*"` (orchestrator 사전 검증) | **0건** GREEN — T-BT는 v3 신규 트랙 |
| 자동매매 적합성 | GREEN — 사용자가 실전 매매 활성화 전 백테스트 통과 의무 (룰 5 정합) |
| AI 매매 결정권 0 | GREEN — DSL 평가만, LLM provider import 0건 (룰 5) |
| 모듈 경계 | GREEN — domain backtest 도메인 모델 sealed/data only · api 단일 책임 (REST + service + JPA persistence) · consumer 미진입 (strategy-eval-pool 공유 의무) · batch 미진입 (1차는 ad-hoc REST run, batch job은 post-MVP) |
| 사용자 메모리 정합 | feedback_no_mock_data.md GREEN (candle 0건 시 503 "데이터 없음") · feedback_swagger_pattern.md GREEN (4단 한국어 + example) · feedback_kis_first_policy.md GREEN (세금·수수료 raw 출처 인용 의무) |

---

## §11. ADR 0047 정합 + ADR 0056 신규

### 11-1. ADR 0047 정합 의무

- BacktestEngine은 **반드시** `JvmStrategyEvaluator.evaluateBacktest()` 경유 (직접 evaluator 호출 금지)
- timeout 1000ms 적용 (ADR 0047 §2-2-A backtest 모드)
- 백테스트는 `auto-disable=false` LOCK (strategy 비활성화 X, 단 백테스트 abort)
- 누적 timeout ≥ 50회 시 백테스트 abort (ADR 0047 §2-5)

### 11-2. ADR 0056 신규 발행 결정

신규 ADR 0056 발행. 결정 영역:
- BacktestEngine 아키텍처 (단일 모델 인터페이스 vs 다중 strategy pattern)
- 세·수수료·슬리피지 모델 정책 (정적 default vs 동적 사용자 설정)
- 룩어헤드 가드 메커니즘 (slice 강제 + runtime 가드)
- timeout 1000ms 결정 사유 (ADR 0047 정합 + abort quota 50)

**파일**: `_workspace/arch/adr/0056-backtest-engine-architecture.md`

`_workspace/arch/adr/README.md` §10-5 신규 카테고리(또는 §6 전략·신호·리스크 확장) Edit으로 항목 추가 의무.

---

## §12. 인터페이스 동결 시점

**본 PR-47 머지 시점** = 다음 LOCK 동결:
1. `BacktestRequest`·`BacktestResult`·`BacktestPosition`·`BacktestTrade`·`BacktestPerformance` 5 도메인 (§4)
2. `BacktestEngine`·`BacktestTaxModel`·`BacktestCommissionModel`·`BacktestSlippageModel` port 4 (§4)
3. `POST /api/backtest/run` 요청·응답 DTO (§5)
4. ADR 0056 본문 채택 사유 (§11-2)

이후 sub-agent PR-48~50 작업 시 위 LOCK 변경 금지 — 변경 발생 시 architect 재발행 사이클 의무.

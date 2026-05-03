package com.mj.domain.strategy.backtest

import com.mj.domain.common.Candle
import com.mj.domain.common.Market
import com.mj.domain.strategy.dsl.BarEvaluationContext
import com.mj.domain.strategy.dsl.BarStrategyEvaluator
import com.mj.domain.strategy.dsl.CompiledExpression
import org.slf4j.LoggerFactory
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 백테스트 엔진 — 도메인 순수 함수.
 *
 * **룩어헤드 차단** (master §2):
 * - 봉 t에서 DSL 평가 시 [BarEvaluationContext]에 `currentIndex = t`만 노출.
 *   → 평가기는 `candles[0..t]`만 참조 가능.
 * - 신호 발생 봉 t의 신호로 봉 t+1 시가에 체결.
 *   → 봉 t에서 t의 close를 보고 t의 close에 체결 = 룩어헤드. 본 엔진에서 **차단**.
 * - 마지막 봉(t = N-1)은 다음 봉이 없으므로 신호 발생해도 진입 X.
 *
 * **adjusted_close** (ADR 0038):
 * - 입력 [BacktestBar.close]는 호출자가 수정종가로 채워 넣는다.
 * - 도메인 엔진은 그대로 사용.
 *
 * **비용 모델** (master §3-5):
 * - [CostModel]로 수수료·거래세·슬리피지를 명시 적용.
 *
 * **결정론**: 동일 입력 → 동일 출력 (난수·시간 의존 없음).
 *
 * **싱글 종목 long-only**:
 * - 동시 1포지션, 신호 false 시 다음 봉 시가에 청산.
 * - 멀티 종목·숏 포지션·분할매수는 R2 이후.
 */
class BacktestEngine(
    private val costModel: CostModel = CostModel.DEFAULT,
) {
    private val log = LoggerFactory.getLogger(BacktestEngine::class.java)
    private val evaluator = BarStrategyEvaluator()

    /**
     * 백테스트 실행.
     *
     * @param symbol 종목 코드 (보고용)
     * @param market KOSPI/KOSDAQ (거래세율 분기)
     * @param bars 봉 시계열 (시간 오름차순). adjusted_close가 close에 채워져 있어야 함.
     * @param initialCapital 시작 자본 (원)
     * @param conditionExpr DSL 조건식 (true=신호 ON=진입 시도, false=신호 OFF=청산 시도)
     * @return [BacktestSummary]
     */
    fun run(
        symbol: String,
        market: Market,
        bars: List<BacktestBar>,
        initialCapital: BigDecimal,
        conditionExpr: String,
    ): BacktestSummary = run(symbol, market, bars, initialCapital, evaluator.compile(conditionExpr))

    /**
     * 컴파일 캐싱이 필요한 호출자용. 동일 식을 여러 종목에 재사용 시 [BarStrategyEvaluator.compile]를
     * 한 번만 호출하고 [CompiledExpression]을 넘긴다.
     */
    fun run(
        symbol: String,
        market: Market,
        bars: List<BacktestBar>,
        initialCapital: BigDecimal,
        compiled: CompiledExpression,
    ): BacktestSummary {
        require(initialCapital.signum() > 0) { "initialCapital must be > 0" }
        if (bars.isEmpty()) {
            return BacktestSummary(
                symbol = symbol,
                trades = emptyList(),
                equityCurve = emptyList(),
                metrics = PerformanceMetrics.EMPTY,
                openPosition = null,
                skippedBars = emptyList(),
            )
        }

        val candleProxies = bars.map { it.toCandleProxy(symbol) }

        var cash = initialCapital
        var openPos: BacktestPosition? = null
        val trades = ArrayList<Trade>()
        val equityCurve = ArrayList<EquityPoint>(bars.size)
        val skipped = ArrayList<Int>()

        for (i in bars.indices) {
            val bar = bars[i]
            val nextBar = bars.getOrNull(i + 1)

            // DSL 평가 (룩어헤드 차단: candles[0..i]만 노출)
            val signal: Boolean? =
                try {
                    compiled.evaluate(BarEvaluationContext(candleProxies, i))
                } catch (e: Exception) {
                    log.debug("backtest: eval skipped at {}/{}: {}", symbol, i, e.message)
                    skipped += i
                    null
                }

            // 다음 봉이 있고 신호 평가 성공 시에만 진입/청산 시도.
            // 마지막 봉(i = N-1)은 nextBar=null → 신호 발생해도 진입 X.
            if (nextBar != null && signal != null) {
                if (signal && openPos == null) {
                    // 진입: 다음 봉 시가에 매수 (슬리피지 + 수수료)
                    val fillPrice = costModel.applyBuySlippage(nextBar.open)
                    val qty = computeBuyableQty(cash, fillPrice)
                    if (qty > 0) {
                        val gross = fillPrice.multiply(BigDecimal(qty))
                        val commission = costModel.buyCommission(gross)
                        cash = cash.subtract(gross).subtract(commission)
                        openPos =
                            BacktestPosition(
                                symbol = symbol,
                                entryDate = nextBar.openTime,
                                entryPrice = fillPrice,
                                shares = qty,
                                entryCommission = commission.setScale(2, RoundingMode.HALF_UP),
                            )
                    }
                } else if (!signal && openPos != null) {
                    // 청산: 다음 봉 시가에 매도
                    val pos = openPos!!
                    val fillPrice = costModel.applySellSlippage(nextBar.open)
                    val gross = fillPrice.multiply(BigDecimal(pos.shares))
                    val sellCost = costModel.sellCost(gross, market)
                    cash = cash.add(gross).subtract(sellCost.commission).subtract(sellCost.tax)
                    trades +=
                        Trade.create(
                            symbol = symbol,
                            entryDate = pos.entryDate,
                            exitDate = nextBar.openTime,
                            entryPrice = pos.entryPrice,
                            exitPrice = fillPrice,
                            shares = pos.shares,
                            entryCommission = pos.entryCommission,
                            exitCommission = sellCost.commission,
                            exitTax = sellCost.tax,
                            reason = Trade.ExitReason.SIGNAL_EXIT,
                        )
                    openPos = null
                }
            }

            // 봉 i 종가 기준 mark-to-market
            val positionValue =
                openPos?.let { it.marketValue(bar.close) } ?: BigDecimal.ZERO
            val equity = cash.add(positionValue)
            equityCurve +=
                EquityPoint(
                    at = bar.openTime,
                    equity = equity.setScale(2, RoundingMode.HALF_UP),
                    cash = cash.setScale(2, RoundingMode.HALF_UP),
                    positionValue = positionValue.setScale(2, RoundingMode.HALF_UP),
                )
        }

        val years = computeYears(bars)
        val metrics =
            PerformanceMetricsCalculator.calculate(
                equityCurve.map { it.equity },
                trades,
                years,
            )

        return BacktestSummary(
            symbol = symbol,
            trades = trades,
            equityCurve = equityCurve,
            metrics = metrics,
            openPosition = openPos,
            skippedBars = skipped,
        )
    }

    private fun computeBuyableQty(
        cash: BigDecimal,
        priceWithSlippage: BigDecimal,
    ): Int {
        if (priceWithSlippage.signum() <= 0) return 0
        val feeFactor = BigDecimal.ONE.add(costModel.commissionRate)
        val maxBudget = cash.divide(feeFactor, MC)
        val raw = maxBudget.divide(priceWithSlippage, 0, RoundingMode.DOWN)
        return raw.toInt().coerceAtLeast(0)
    }

    private fun computeYears(bars: List<BacktestBar>): BigDecimal {
        if (bars.size < 2) return BigDecimal.ZERO
        val first = bars.first().openTime
        val last = bars.last().openTime
        val seconds = last.epochSecond - first.epochSecond
        if (seconds <= 0) return BigDecimal.ZERO
        // 365.25 days = 31557600 seconds
        return BigDecimal(seconds).divide(BigDecimal("31557600"), MC)
    }

    private fun BacktestBar.toCandleProxy(symbol: String): Candle =
        Candle(
            code = symbol,
            interval = com.mj.domain.common.Interval.D1,
            openTime = openTime,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
            tradeCount = 0,
        )

    companion object {
        private val MC = MathContext(12, RoundingMode.HALF_UP)
    }
}

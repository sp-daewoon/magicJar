package com.mj.batch.job.backtest

import com.mj.domain.common.BacktestConfig
import com.mj.domain.common.BacktestRequest
import com.mj.domain.common.BacktestResult
import com.mj.domain.common.Candle
import com.mj.domain.common.Market
import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.journal.TradeRef
import com.mj.domain.market.instrument.port.InstrumentRepository
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.order.port.OrderSender
import com.mj.domain.strategy.backtest.BacktestExecutionContext
import com.mj.domain.strategy.backtest.BacktestExecutionContextHolder
import com.mj.domain.strategy.backtest.MetricsCalculator
import com.mj.domain.strategy.dsl.BarEvaluationContext
import com.mj.domain.strategy.dsl.BarStrategyEvaluator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

/**
 * 백테스트 엔진.
 *
 * 단순 long-only 모델:
 * - 조건식이 true면 진입(BUY) — 신호 봉의 다음 봉 시가에 체결.
 * - 조건식이 false인데 포지션 보유면 청산(SELL) — 신호 봉의 다음 봉 시가에 체결.
 * - 종목별 동시 1포지션. 자본은 `initialCapitalKrw`를 universe 종목 수로 균등 분배.
 */
@Component
class BacktestEngine(
    private val candleRepository: CandleRepository,
    private val instrumentCatalog: InstrumentRepository,
    private val orderSender: OrderSender,
    private val clock: Clock = Clock.systemUTC(),
) {
    private val log = LoggerFactory.getLogger(BacktestEngine::class.java)
    private val evaluator = BarStrategyEvaluator()

    fun run(request: BacktestRequest): BacktestResult {
        require(request.universe.isNotEmpty()) { "universe must not be empty" }
        val compiled = evaluator.compile(request.conditionExpr)
        val perCodeCapital =
            request.config.initialCapitalKrw
                .divide(BigDecimal(request.universe.size), MC)

        val perCodeEquityCurves = HashMap<String, List<DailyEquityPoint>>()
        val roundTrips = mutableListOf<BigDecimal>()
        val tradeRefs = mutableListOf<TradeRef>()

        val zone = ZoneId.of("Asia/Seoul")
        val fromInstant = request.from.atStartOfDay(zone).toInstant()
        val toInstant =
            request.to
                .atStartOfDay(zone)
                .plusDays(1)
                .toInstant()

        for (code in request.universe) {
            val candles = candleRepository.find(code, request.interval, fromInstant, toInstant)
            if (candles.isEmpty()) {
                log.warn("backtest: no candles for code={} range=[{}..{}]", code, request.from, request.to)
                continue
            }
            val market = instrumentCatalog.findByCode(code)?.market ?: Market.KOSPI
            val curve =
                runForCode(
                    candles = candles,
                    code = code,
                    market = market,
                    initialCapital = perCodeCapital,
                    config = request.config,
                    compiled = compiled,
                    onRoundTripPnl = { pnl, ref ->
                        roundTrips += pnl
                        if (ref != null) tradeRefs += ref
                    },
                )
            perCodeEquityCurves[code] = curve
        }

        val mergedCurve = mergeEquityCurves(perCodeEquityCurves, request.config.initialCapitalKrw, request.universe.size)
        val metrics = MetricsCalculator.calculate(mergedCurve, roundTrips, request.from, request.to)
        return BacktestResult(
            strategy = request.strategy,
            from = request.from,
            to = request.to,
            metrics = metrics,
            trades = tradeRefs,
        )
    }

    private fun runForCode(
        candles: List<Candle>,
        code: String,
        market: Market,
        initialCapital: BigDecimal,
        config: BacktestConfig,
        compiled: com.mj.domain.strategy.dsl.CompiledExpression,
        onRoundTripPnl: (BigDecimal, TradeRef?) -> Unit,
    ): List<DailyEquityPoint> {
        var cash = initialCapital
        var qty = 0
        var entryPrice = BigDecimal.ZERO
        val curve = ArrayList<DailyEquityPoint>(candles.size)

        for (i in candles.indices) {
            val candle = candles[i]
            val nextOpen = candles.getOrNull(i + 1)?.open
            val signal =
                try {
                    compiled.evaluate(BarEvaluationContext(candles, i))
                } catch (e: Exception) {
                    log.debug("backtest: eval skipped at {}/{}: {}", code, i, e.message)
                    false
                }

            if (nextOpen != null) {
                if (signal && qty == 0) {
                    val tradable = computeBuyableQty(cash, nextOpen, config)
                    if (tradable > 0) {
                        val orderResult =
                            sendOrder(
                                side = Side.BUY,
                                qty = tradable,
                                code = code,
                                nextOpen = nextOpen,
                                fillAt = candles[i + 1].openTime,
                                slippageBp = config.slippageBp,
                            )
                        if (orderResult.resultStatus == OrderStatus.FILLED) {
                            val px = orderResult.avgFillPrice ?: nextOpen
                            val gross = px.multiply(BigDecimal(tradable))
                            val fee = gross.multiply(config.feeRate, MC)
                            cash = cash.subtract(gross).subtract(fee)
                            qty = tradable
                            entryPrice = px
                        }
                    }
                } else if (!signal && qty > 0) {
                    val orderResult =
                        sendOrder(
                            side = Side.SELL,
                            qty = qty,
                            code = code,
                            nextOpen = nextOpen,
                            fillAt = candles[i + 1].openTime,
                            slippageBp = config.slippageBp,
                        )
                    if (orderResult.resultStatus == OrderStatus.FILLED) {
                        val px = orderResult.avgFillPrice ?: nextOpen
                        val gross = px.multiply(BigDecimal(qty))
                        val fee = gross.multiply(config.feeRate, MC)
                        val taxRate = if (market == Market.KOSPI) config.taxRateKospi else config.taxRateKosdaq
                        val tax = gross.multiply(taxRate, MC)
                        val pnl =
                            px
                                .subtract(entryPrice)
                                .multiply(BigDecimal(qty))
                                .subtract(fee)
                                .subtract(tax)
                        cash = cash.add(gross.subtract(fee).subtract(tax))
                        onRoundTripPnl(pnl, null)
                        qty = 0
                        entryPrice = BigDecimal.ZERO
                    }
                }
            }

            val markPrice = candle.close
            val equity = cash.add(BigDecimal(qty).multiply(markPrice))
            curve += DailyEquityPoint(candle.openTime, equity)
        }

        return curve
    }

    private fun sendOrder(
        side: Side,
        qty: Int,
        code: String,
        nextOpen: BigDecimal,
        fillAt: Instant,
        slippageBp: BigDecimal,
    ) = run {
        val order =
            Order(
                id = UUID.randomUUID(),
                code = code,
                side = side,
                qty = qty,
                price = null,
                type = OrderType.MARKET,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(clock),
            )
        BacktestExecutionContextHolder.setContext(
            BacktestExecutionContext(
                nextBarOpen = nextOpen,
                slippageBp = slippageBp,
                fillAt = fillAt,
            ),
        )
        try {
            orderSender.send(order)
        } finally {
            BacktestExecutionContextHolder.clear()
        }
    }

    private fun computeBuyableQty(
        cash: BigDecimal,
        priceWithSlippage: BigDecimal,
        config: BacktestConfig,
    ): Int {
        if (priceWithSlippage.signum() <= 0) return 0
        val feeFactor = BigDecimal.ONE.add(config.feeRate)
        val maxBudget = cash.divide(feeFactor, MC)
        val raw = maxBudget.divide(priceWithSlippage, 0, RoundingMode.DOWN)
        return raw.toInt().coerceAtLeast(0)
    }

    private fun mergeEquityCurves(
        curves: Map<String, List<DailyEquityPoint>>,
        initialCapital: BigDecimal,
        universeSize: Int,
    ): List<BigDecimal> {
        if (curves.isEmpty()) return listOf(initialCapital)
        val perCodeCapital = initialCapital.divide(BigDecimal(universeSize), MC)
        val timeline = sortedSetOf<Instant>()
        curves.values.forEach { c -> c.forEach { timeline += it.at } }
        val byCode = curves.mapValues { (_, list) -> list.associateBy { it.at } }
        val result = ArrayList<BigDecimal>(timeline.size)
        val lastByCode = HashMap<String, BigDecimal>()
        for (code in curves.keys) lastByCode[code] = perCodeCapital
        for (t in timeline) {
            var sum = BigDecimal.ZERO
            for ((code, mapByT) in byCode) {
                val v = mapByT[t]?.equity ?: lastByCode[code] ?: perCodeCapital
                lastByCode[code] = v
                sum = sum.add(v)
            }
            result += sum
        }
        return if (result.isEmpty()) listOf(initialCapital) else result
    }

    companion object {
        private val MC = MathContext(12, RoundingMode.HALF_UP)
    }
}

internal data class DailyEquityPoint(
    val at: Instant,
    val equity: BigDecimal,
)

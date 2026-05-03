package com.mj.domain.strategy.backtest

import com.mj.domain.common.Market
import com.mj.domain.strategy.dsl.BarEvaluationContext
import com.mj.domain.strategy.dsl.BarStrategyEvaluator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * 룩어헤드 차단 검증.
 *
 * BacktestEngine 호출 시 DSL 평가기에 currentIndex를 i로만 노출.
 * → 평가기는 candles[0..i]만 참조 가능. candles[i+1..]에 미래 정보를 넣어도 결과 동일.
 *
 * 시나리오:
 * 1. 정상 시계열로 백테스트 실행 → trades A
 * 2. 동일 봉 시계열의 "미래 부분만 변경" → trades B
 * 3. 봉 i 신호는 candles[0..i]만 보고 결정해야 하므로 미래 변경에도 신호는 동일 → A와 B의 trade 동일
 *
 * 단, BacktestEngine은 미래 봉 i+1의 open으로 체결가를 정하기 때문에
 * 체결가는 미래 변경에 따라 달라짐. 신호 발생 봉(t)는 동일해야 한다는 핵심.
 */
class BacktestLookaheadTest {
    private val ts0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun bar(
        i: Int,
        close: BigDecimal,
        open: BigDecimal = close,
    ): BacktestBar =
        BacktestBar(
            openTime = ts0.plus(i.toLong(), ChronoUnit.DAYS),
            open = open,
            high = close,
            low = close,
            close = close,
            volume = 1_000_000L,
        )

    @Test
    fun `signal on bar i is invariant to changes in bars after i`() {
        val evaluator = BarStrategyEvaluator()
        val compiled = evaluator.compile("close > 100")

        // 시계열 A: bar 0~5 close [50, 80, 150, 200, 90, 70]
        val barsA =
            listOf(
                bar(0, BigDecimal("50")),
                bar(1, BigDecimal("80")),
                bar(2, BigDecimal("150")),
                bar(3, BigDecimal("200")),
                bar(4, BigDecimal("90")),
                bar(5, BigDecimal("70")),
            )

        // 시계열 B: bar 0~2는 동일, bar 3 이후 다른 값으로 변경
        val barsB =
            listOf(
                bar(0, BigDecimal("50")),
                bar(1, BigDecimal("80")),
                bar(2, BigDecimal("150")),
                bar(3, BigDecimal("9999")), // 변경
                bar(4, BigDecimal("9999")), // 변경
                bar(5, BigDecimal("9999")), // 변경
            )

        // 봉 0~2 평가는 두 시계열에서 동일해야 함 (미래 봉 비참조 = 룩어헤드 차단)
        val candleProxiesA =
            barsA.map {
                com.mj.domain.common.Candle(
                    code = "T",
                    interval = com.mj.domain.common.Interval.D1,
                    openTime = it.openTime,
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                    tradeCount = 0,
                )
            }
        val candleProxiesB =
            barsB.map {
                com.mj.domain.common.Candle(
                    code = "T",
                    interval = com.mj.domain.common.Interval.D1,
                    openTime = it.openTime,
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                    tradeCount = 0,
                )
            }

        for (i in 0..2) {
            val sigA = compiled.evaluate(BarEvaluationContext(candleProxiesA, i))
            val sigB = compiled.evaluate(BarEvaluationContext(candleProxiesB, i))
            sigA shouldBe sigB
        }
    }

    @Test
    fun `truncated series produces same signal sequence up to truncation point`() {
        // 시계열을 봉 i까지 자르고 평가했을 때, 전체 시계열의 봉 i 평가와 동일해야 한다.
        val evaluator = BarStrategyEvaluator()
        val compiled = evaluator.compile("SMA(3) > 100")

        val fullBars =
            listOf(
                bar(0, BigDecimal("50")),
                bar(1, BigDecimal("80")),
                bar(2, BigDecimal("150")),
                bar(3, BigDecimal("200")),
                bar(4, BigDecimal("90")),
                bar(5, BigDecimal("70")),
            )

        val proxies =
            fullBars.map {
                com.mj.domain.common.Candle(
                    code = "T",
                    interval = com.mj.domain.common.Interval.D1,
                    openTime = it.openTime,
                    open = it.open,
                    high = it.high,
                    low = it.low,
                    close = it.close,
                    volume = it.volume,
                    tradeCount = 0,
                )
            }

        for (i in 2 until fullBars.size) {
            val truncatedProxies = proxies.subList(0, i + 1)
            val sigFull = compiled.evaluate(BarEvaluationContext(proxies, i))
            val sigTruncated = compiled.evaluate(BarEvaluationContext(truncatedProxies, i))
            sigFull shouldBe sigTruncated
        }
    }

    @Test
    fun `engine entry timing — signal at bar i means fill at bar i+1 open`() {
        // 신호 봉 t의 close는 진입가에 영향 없어야 함. 진입가는 t+1의 open.
        val bars =
            listOf(
                bar(0, BigDecimal("50"), open = BigDecimal("50")),
                // bar 0: signal close>100 = false → no entry
                bar(1, BigDecimal("150"), open = BigDecimal("100")),
                // bar 1: close=150 > 100 → ON. nextBar.open = bar2.open = ?
                bar(2, BigDecimal("200"), open = BigDecimal("180")),
                // bar 1 신호 → bar 2 open=180 매수
                bar(3, BigDecimal("90"), open = BigDecimal("210")),
                // bar 2 still ON. bar 3 close=90 OFF → bar 4 open 매도
                bar(4, BigDecimal("80"), open = BigDecimal("85")),
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        s.trades.size shouldBe 1
        s.trades[0].entryPrice shouldBe BigDecimal("180.00") // bar 2 open
        s.trades[0].exitPrice shouldBe BigDecimal("85.00") // bar 4 open
        s.trades[0].entryDate shouldBe bars[2].openTime
        s.trades[0].exitDate shouldBe bars[4].openTime
    }

    @Test
    fun `signal on last bar produces no entry (no next bar to fill)`() {
        // 마지막 봉만 신호 ON.
        val bars =
            listOf(
                bar(0, BigDecimal("50")),
                bar(1, BigDecimal("60")),
                bar(2, BigDecimal("150")), // 마지막 봉만 ON
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        s.trades.size shouldBe 0
        s.openPosition shouldBe null
    }
}

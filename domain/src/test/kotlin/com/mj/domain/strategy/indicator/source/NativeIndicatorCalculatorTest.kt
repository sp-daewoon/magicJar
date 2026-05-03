package com.mj.domain.strategy.indicator.source

import com.mj.domain.strategy.CandleFixtures
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import io.kotest.matchers.collections.shouldHaveAtLeastSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

/**
 * NativeIndicatorCalculator 단위 테스트.
 *
 * 교과서 경계값:
 * - RSI: 0~100 범위, 첫 period 포인트는 null
 * - MA: period-1개 미만은 null
 * - MACD: signal 포인트 전에는 MACD line만 존재
 * - BB: 3 series(upper/middle/lower)
 * - OBV: 누적, 모든 봉 포함
 * - VWAP: 모든 봉 포함
 */
class NativeIndicatorCalculatorTest {
    private val calc = NativeIndicatorCalculator()
    private val candles = CandleFixtures.loadSampleSamsung()

    @Test
    fun `supports returns true for known ids`() {
        calc.supports(IndicatorSpec("ma")) shouldBe true
        calc.supports(IndicatorSpec("ema")) shouldBe true
        calc.supports(IndicatorSpec("rsi")) shouldBe true
        calc.supports(IndicatorSpec("macd")) shouldBe true
        calc.supports(IndicatorSpec("bb")) shouldBe true
    }

    @Test
    fun `supports returns false for unknown id`() {
        calc.supports(IndicatorSpec("unknown_indicator")) shouldBe false
        calc.supports(IndicatorSpec("supertrend")) shouldBe false
    }

    @Test
    fun `MA RSI period=14 — series count positive and within candle range`() {
        val maResult = calc.calculate(IndicatorSpec("ma", mapOf("period" to 14)), candles)
        maResult.series.size shouldNotBe 0
        // MA: candles.size - 13 개 유효
        maResult.series.size shouldBe (candles.size - 13)
        maResult.source shouldBe "native"

        val rsiResult = calc.calculate(IndicatorSpec("rsi", mapOf("period" to 14)), candles)
        rsiResult.series.size shouldNotBe 0
        // RSI 값은 0~100
        rsiResult.series.forEach { point ->
            val v = point.values["rsi"]!!
            require(v.toDouble() in 0.0..100.0) { "RSI out of bounds: $v" }
        }
    }

    @Test
    fun `EMA period=20 — series starts from index 19`() {
        val result = calc.calculate(IndicatorSpec("ema", mapOf("period" to 20)), candles)
        result.series.size shouldBe (candles.size - 19)
        result.series.first().values["ema"] shouldNotBe null
    }

    @Test
    fun `MACD default — returns multi-key values with macd and signal`() {
        val result =
            calc.calculate(
                IndicatorSpec("macd", mapOf("fast" to 12, "slow" to 26, "signal" to 9)),
                candles,
            )
        result.series shouldHaveAtLeastSize 1
        // signal line 존재하는 점에서 세 키 모두 있어야 함
        val lastPoint = result.series.last()
        lastPoint.values.containsKey("macd") shouldBe true
        lastPoint.values.containsKey("signal") shouldBe true
        lastPoint.values.containsKey("histogram") shouldBe true
    }

    @Test
    fun `BB period=20 — returns upper, middle, lower keys`() {
        val result =
            calc.calculate(
                IndicatorSpec("bb", mapOf("period" to 20, "std_dev" to 2.0)),
                candles,
            )
        result.series shouldHaveAtLeastSize 1
        val point = result.series.last()
        point.values.containsKey("bb_upper") shouldBe true
        point.values.containsKey("bb_middle") shouldBe true
        point.values.containsKey("bb_lower") shouldBe true
        // upper > middle > lower
        val upper = point.values["bb_upper"]!!
        val middle = point.values["bb_middle"]!!
        val lower = point.values["bb_lower"]!!
        require(upper >= middle) { "BB upper < middle" }
        require(middle >= lower) { "BB middle < lower" }
    }

    @Test
    fun `OBV — all candles produce a point`() {
        val result = calc.calculate(IndicatorSpec("obv"), candles)
        result.series.size shouldBe candles.size
        result.series.first().values["obv"] shouldBe java.math.BigDecimal.ZERO
    }

    @Test
    fun `VWAP — all candles produce a point`() {
        val result = calc.calculate(IndicatorSpec("vwap"), candles)
        result.series.size shouldBe candles.size
        result.series.forEach { it.values["vwap"] shouldNotBe null }
    }

    @Test
    fun `empty candles returns empty series`() {
        val result = calc.calculate(IndicatorSpec("rsi"), emptyList())
        result.series.size shouldBe 0
    }

    @Test
    fun `availableIndicators contains all 14 supported ids`() {
        val ids = calc.availableIndicators().map { it.id }.toSet()
        listOf(
            "ma",
            "ema",
            "rsi",
            "macd",
            "bb",
            "vwap",
            "stochastic_k",
            "stochastic_d",
            "atr",
            "cci",
            "obv",
            "disparity",
            "adx",
            "volume_ma",
        ).forEach {
            ids.contains(it) shouldBe true
        }
    }
}

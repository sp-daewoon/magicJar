package com.mj.domain.strategy.indicator

import com.mj.domain.common.Candle
import java.math.BigDecimal

/**
 * MACD(fast=12, slow=26, signal=9).
 *
 * macdLine = EMA(fast) - EMA(slow)
 * signalLine = EMA(macdLine, signal)
 * histogram = macdLine - signalLine
 *
 * fast, slow, signal 어느 값이라도 미정의면 null.
 */
data class MacdResult(
    val macd: List<BigDecimal?>,
    val signal: List<BigDecimal?>,
    val histogram: List<BigDecimal?>,
)

object Macd {
    fun ofClose(
        candles: List<Candle>,
        fast: Int = 12,
        slow: Int = 26,
        signal: Int = 9,
    ): MacdResult = of(candles.map { it.close }, fast, slow, signal)

    fun of(
        values: List<BigDecimal>,
        fast: Int = 12,
        slow: Int = 26,
        signal: Int = 9,
    ): MacdResult {
        require(fast > 0 && slow > 0 && signal > 0) { "MACD periods must be positive" }
        require(fast < slow) { "fast must be smaller than slow" }
        val emaFast = Ema.of(values, fast)
        val emaSlow = Ema.of(values, slow)
        val macdLine =
            values.indices.map { i ->
                val f = emaFast[i]
                val s = emaSlow[i]
                if (f != null && s != null) f.subtract(s) else null
            }
        // signal = EMA(macdLine), signal seed: first `signal` non-null macd values' SMA
        val signalLine = emaOfNullableSeries(macdLine, signal)
        val hist =
            macdLine.indices.map { i ->
                val m = macdLine[i]
                val s = signalLine[i]
                if (m != null && s != null) m.subtract(s) else null
            }
        return MacdResult(macdLine, signalLine, hist)
    }

    private fun emaOfNullableSeries(
        series: List<BigDecimal?>,
        period: Int,
    ): List<BigDecimal?> {
        val out = arrayOfNulls<BigDecimal>(series.size)
        // find first index where series is non-null
        val firstIdx = series.indexOfFirst { it != null }
        if (firstIdx < 0) return out.toList()
        val available = series.size - firstIdx
        if (available < period) return out.toList()
        val alpha =
            IndicatorMath.TWO.divide(
                BigDecimal(period + 1),
                IndicatorMath.MC,
            )
        val oneMinusAlpha = BigDecimal.ONE.subtract(alpha)
        var sum = BigDecimal.ZERO
        for (i in firstIdx until firstIdx + period) sum = sum.add(series[i]!!)
        var ema = sum.divide(BigDecimal(period), IndicatorMath.MC)
        out[firstIdx + period - 1] = ema
        for (i in firstIdx + period until series.size) {
            val v = series[i] ?: continue
            ema =
                v
                    .multiply(alpha, IndicatorMath.MC)
                    .add(ema.multiply(oneMinusAlpha, IndicatorMath.MC), IndicatorMath.MC)
            out[i] = ema
        }
        return out.toList()
    }
}

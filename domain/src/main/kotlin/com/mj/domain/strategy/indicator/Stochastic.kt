package com.mj.domain.strategy.indicator

import com.mj.domain.common.Candle
import java.math.BigDecimal

/**
 * Fast Stochastic Oscillator. %K(k=14), %D(d=3) (단순 이동평균 스무딩).
 *
 * %K = 100 * (close - lowestLowK) / (highestHighK - lowestLowK)
 * %D = SMA(%K, d)
 */
data class StochasticResult(
    val k: List<BigDecimal?>,
    val d: List<BigDecimal?>,
)

object Stochastic {
    fun of(
        candles: List<Candle>,
        kPeriod: Int = 14,
        dPeriod: Int = 3,
    ): StochasticResult {
        require(kPeriod > 0 && dPeriod > 0) { "periods must be positive" }
        val n = candles.size
        val kSeries = arrayOfNulls<BigDecimal>(n)
        if (n >= kPeriod) {
            for (i in kPeriod - 1 until n) {
                var hh = candles[i].high
                var ll = candles[i].low
                for (j in i - kPeriod + 1..i) {
                    if (candles[j].high > hh) hh = candles[j].high
                    if (candles[j].low < ll) ll = candles[j].low
                }
                val range = hh.subtract(ll)
                kSeries[i] =
                    if (range.signum() == 0) {
                        BigDecimal("50")
                    } else {
                        candles[i]
                            .close
                            .subtract(ll)
                            .multiply(IndicatorMath.HUNDRED, IndicatorMath.MC)
                            .divide(range, IndicatorMath.MC)
                    }
            }
        }
        val dSeries = smaOfNullable(kSeries.toList(), dPeriod)
        return StochasticResult(kSeries.toList(), dSeries)
    }

    private fun smaOfNullable(
        series: List<BigDecimal?>,
        period: Int,
    ): List<BigDecimal?> {
        val out = arrayOfNulls<BigDecimal>(series.size)
        val firstIdx = series.indexOfFirst { it != null }
        if (firstIdx < 0) return out.toList()
        if (series.size - firstIdx < period) return out.toList()
        val periodBd = BigDecimal(period)
        var sum = BigDecimal.ZERO
        for (i in firstIdx until firstIdx + period) sum = sum.add(series[i]!!)
        out[firstIdx + period - 1] = sum.divide(periodBd, IndicatorMath.MC)
        for (i in firstIdx + period until series.size) {
            val v = series[i] ?: continue
            sum = sum.add(v).subtract(series[i - period]!!)
            out[i] = sum.divide(periodBd, IndicatorMath.MC)
        }
        return out.toList()
    }
}

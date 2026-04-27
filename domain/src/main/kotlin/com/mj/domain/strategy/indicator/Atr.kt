package com.mj.domain.strategy.indicator

import com.mj.domain.common.Candle
import java.math.BigDecimal

/**
 * ATR(Average True Range, period=14, Wilder smoothing).
 *
 * TR_i = max(high - low, |high - prevClose|, |low - prevClose|)
 * 첫 ATR(인덱스 = period)은 TR의 단순 평균. 이후는 Wilder smoothing.
 */
object Atr {
    fun of(
        candles: List<Candle>,
        period: Int = 14,
    ): List<BigDecimal?> {
        require(period > 0) { "period must be positive" }
        val n = candles.size
        val out = arrayOfNulls<BigDecimal>(n)
        if (n <= period) return out.toList()
        val tr = Array(n) { BigDecimal.ZERO }
        for (i in 1 until n) {
            val c = candles[i]
            val prevClose = candles[i - 1].close
            val a = c.high.subtract(c.low)
            val b = c.high.subtract(prevClose).abs()
            val d = c.low.subtract(prevClose).abs()
            tr[i] = listOf(a, b, d).max()
        }
        var sum = BigDecimal.ZERO
        for (i in 1..period) sum = sum.add(tr[i])
        var atr = sum.divide(BigDecimal(period), IndicatorMath.MC)
        out[period] = atr
        val periodBd = BigDecimal(period)
        for (i in period + 1 until n) {
            atr =
                atr
                    .multiply(BigDecimal(period - 1), IndicatorMath.MC)
                    .add(tr[i])
                    .divide(periodBd, IndicatorMath.MC)
            out[i] = atr
        }
        return out.toList()
    }
}

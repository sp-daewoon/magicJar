package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * CCI(Commodity Channel Index, period=20).
 *
 * TP = (high + low + close) / 3
 * SMA_TP = SMA(TP, period)
 * meanDeviation = (1/period) * Σ |TP_i - SMA_TP|
 * CCI = (TP - SMA_TP) / (0.015 * meanDeviation)
 */
object Cci {
    private val K: BigDecimal = BigDecimal("0.015")
    private val THREE: BigDecimal = BigDecimal("3")

    fun of(
        candles: List<Candle>,
        period: Int = 20,
    ): List<BigDecimal?> {
        require(period > 0) { "period must be positive" }
        val n = candles.size
        val out = arrayOfNulls<BigDecimal>(n)
        if (n < period) return out.toList()
        val tp =
            candles.map { c ->
                c.high
                    .add(c.low)
                    .add(c.close)
                    .divide(THREE, IndicatorMath.MC)
            }
        val smaTp = Sma.of(tp, period)
        val periodBd = BigDecimal(period)
        for (i in period - 1 until n) {
            val mean = smaTp[i] ?: continue
            var madSum = BigDecimal.ZERO
            for (j in i - period + 1..i) {
                madSum = madSum.add(tp[j].subtract(mean).abs())
            }
            val mad = madSum.divide(periodBd, IndicatorMath.MC)
            if (mad.signum() == 0) {
                out[i] = BigDecimal.ZERO
            } else {
                out[i] =
                    tp[i]
                        .subtract(mean)
                        .divide(K.multiply(mad, IndicatorMath.MC), IndicatorMath.MC)
            }
        }
        return out.toList()
    }
}

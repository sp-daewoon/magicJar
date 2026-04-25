package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * 볼린저 밴드(period=20, k=2). 표준편차는 표본 분산이 아닌 모집단 분산(population)을 사용한다.
 *
 * upper = sma + k*sigma, lower = sma - k*sigma.
 * 기간 부족 구간은 모두 null.
 */
data class BollingerResult(
    val middle: List<BigDecimal?>,
    val upper: List<BigDecimal?>,
    val lower: List<BigDecimal?>,
)

object BollingerBands {
    fun ofClose(
        candles: List<Candle>,
        period: Int = 20,
        k: BigDecimal = BigDecimal("2"),
    ): BollingerResult = of(candles.map { it.close }, period, k)

    fun of(
        values: List<BigDecimal>,
        period: Int = 20,
        k: BigDecimal = BigDecimal("2"),
    ): BollingerResult {
        require(period > 0) { "period must be positive" }
        val middle = Sma.of(values, period)
        val upper = arrayOfNulls<BigDecimal>(values.size)
        val lower = arrayOfNulls<BigDecimal>(values.size)
        if (values.size >= period) {
            val periodBd = BigDecimal(period)
            for (i in period - 1 until values.size) {
                val mean = middle[i] ?: continue
                var sumSq = BigDecimal.ZERO
                for (j in i - period + 1..i) {
                    val diff = values[j].subtract(mean)
                    sumSq = sumSq.add(diff.multiply(diff, IndicatorMath.MC))
                }
                val variance = sumSq.divide(periodBd, IndicatorMath.MC)
                val sigma = IndicatorMath.sqrt(variance)
                val band = sigma.multiply(k, IndicatorMath.MC)
                upper[i] = mean.add(band)
                lower[i] = mean.subtract(band)
            }
        }
        return BollingerResult(middle, upper.toList(), lower.toList())
    }
}

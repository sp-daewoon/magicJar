package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * 단순 이동평균(Simple Moving Average).
 * 인덱스 i에서 i-period+1 ~ i 까지 평균 close.
 * 기간 부족 구간(i < period-1)은 null.
 */
object Sma {
    fun ofClose(
        candles: List<Candle>,
        period: Int,
    ): List<BigDecimal?> = of(candles.map { it.close }, period)

    fun of(
        values: List<BigDecimal>,
        period: Int,
    ): List<BigDecimal?> {
        require(period > 0) { "period must be positive: $period" }
        if (values.isEmpty()) return emptyList()
        val out = arrayOfNulls<BigDecimal>(values.size)
        if (values.size < period) return out.toList()
        var sum = BigDecimal.ZERO
        for (i in 0 until period) {
            sum = sum.add(values[i])
        }
        out[period - 1] = sum.divide(BigDecimal(period), IndicatorMath.MC)
        for (i in period until values.size) {
            sum = sum.add(values[i]).subtract(values[i - period])
            out[i] = sum.divide(BigDecimal(period), IndicatorMath.MC)
        }
        return out.toList()
    }
}

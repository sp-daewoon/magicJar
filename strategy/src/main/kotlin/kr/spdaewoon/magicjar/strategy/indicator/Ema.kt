package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * 지수 이동평균(Wilder's smoothing 변형이 아닌 일반 EMA: alpha = 2 / (period + 1)).
 *
 * 시드는 첫 period 봉의 SMA를 사용한다(TA4J 관습). period 미만 구간은 null.
 */
object Ema {
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
        val alpha =
            IndicatorMath.TWO.divide(
                BigDecimal(period + 1),
                IndicatorMath.MC,
            )
        val oneMinusAlpha = BigDecimal.ONE.subtract(alpha)
        // seed = SMA of first `period` values
        var sum = BigDecimal.ZERO
        for (i in 0 until period) sum = sum.add(values[i])
        var ema = sum.divide(BigDecimal(period), IndicatorMath.MC)
        out[period - 1] = ema
        for (i in period until values.size) {
            ema =
                values[i]
                    .multiply(alpha, IndicatorMath.MC)
                    .add(ema.multiply(oneMinusAlpha, IndicatorMath.MC), IndicatorMath.MC)
            out[i] = ema
        }
        return out.toList()
    }
}

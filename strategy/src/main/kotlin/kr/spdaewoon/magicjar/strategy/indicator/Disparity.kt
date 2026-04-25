package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * 이격도(Disparity, period=20). 100 * close / SMA(close, period).
 * 100 위면 이동평균 대비 강세, 100 아래면 약세.
 */
object Disparity {
    fun of(
        candles: List<Candle>,
        period: Int = 20,
    ): List<BigDecimal?> {
        require(period > 0) { "period must be positive" }
        val sma = Sma.ofClose(candles, period)
        return candles.indices.map { i ->
            val s = sma[i] ?: return@map null
            if (s.signum() == 0) {
                null
            } else {
                candles[i]
                    .close
                    .multiply(IndicatorMath.HUNDRED, IndicatorMath.MC)
                    .divide(s, IndicatorMath.MC)
            }
        }
    }
}

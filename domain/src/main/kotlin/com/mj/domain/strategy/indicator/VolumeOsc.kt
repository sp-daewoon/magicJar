package com.mj.domain.strategy.indicator

import com.mj.domain.common.Candle
import java.math.BigDecimal

/**
 * 거래량 오실레이터(Percentage Volume Oscillator, fast=12, slow=26).
 * (EMA_fast - EMA_slow) / EMA_slow * 100.
 *
 * 추가로 평균거래량(AVG_VOLUME)도 같은 패키지에서 제공한다.
 */
object VolumeOsc {
    fun of(
        candles: List<Candle>,
        fast: Int = 12,
        slow: Int = 26,
    ): List<BigDecimal?> {
        require(fast > 0 && slow > 0 && fast < slow) { "fast must be smaller than slow" }
        val volumes = candles.map { BigDecimal(it.volume) }
        val emaFast = Ema.of(volumes, fast)
        val emaSlow = Ema.of(volumes, slow)
        return candles.indices.map { i ->
            val f = emaFast[i]
            val s = emaSlow[i]
            if (f != null && s != null && s.signum() != 0) {
                f
                    .subtract(s)
                    .multiply(IndicatorMath.HUNDRED, IndicatorMath.MC)
                    .divide(s, IndicatorMath.MC)
            } else {
                null
            }
        }
    }

    /** SMA(VOLUME, period). */
    fun avgVolume(
        candles: List<Candle>,
        period: Int = 20,
    ): List<BigDecimal?> = Sma.of(candles.map { BigDecimal(it.volume) }, period)
}

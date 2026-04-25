package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * Wilder RSI(period). 기본 14.
 *
 * RSI = 100 - 100 / (1 + RS), RS = avgGain / avgLoss.
 * 첫 period 봉 동안의 단순 평균을 시드로, 이후 Wilder smoothing.
 *
 * 인덱스 i < period 구간은 null.
 */
object Rsi {
    fun ofClose(
        candles: List<Candle>,
        period: Int = 14,
    ): List<BigDecimal?> = of(candles.map { it.close }, period)

    fun of(
        values: List<BigDecimal>,
        period: Int = 14,
    ): List<BigDecimal?> {
        require(period > 0) { "period must be positive: $period" }
        if (values.size <= period) return List(values.size) { null }
        val out = arrayOfNulls<BigDecimal>(values.size)
        var gainSum = BigDecimal.ZERO
        var lossSum = BigDecimal.ZERO
        for (i in 1..period) {
            val diff = values[i].subtract(values[i - 1])
            if (diff.signum() > 0) gainSum = gainSum.add(diff) else lossSum = lossSum.add(diff.negate())
        }
        val periodBd = BigDecimal(period)
        var avgGain = gainSum.divide(periodBd, IndicatorMath.MC)
        var avgLoss = lossSum.divide(periodBd, IndicatorMath.MC)
        out[period] = computeRsi(avgGain, avgLoss)
        for (i in period + 1 until values.size) {
            val diff = values[i].subtract(values[i - 1])
            val gain = if (diff.signum() > 0) diff else BigDecimal.ZERO
            val loss = if (diff.signum() < 0) diff.negate() else BigDecimal.ZERO
            avgGain =
                avgGain
                    .multiply(BigDecimal(period - 1), IndicatorMath.MC)
                    .add(gain)
                    .divide(periodBd, IndicatorMath.MC)
            avgLoss =
                avgLoss
                    .multiply(BigDecimal(period - 1), IndicatorMath.MC)
                    .add(loss)
                    .divide(periodBd, IndicatorMath.MC)
            out[i] = computeRsi(avgGain, avgLoss)
        }
        return out.toList()
    }

    private fun computeRsi(
        avgGain: BigDecimal,
        avgLoss: BigDecimal,
    ): BigDecimal {
        if (avgLoss.signum() == 0) return IndicatorMath.HUNDRED
        val rs = avgGain.divide(avgLoss, IndicatorMath.MC)
        val onePlusRs = BigDecimal.ONE.add(rs)
        return IndicatorMath.HUNDRED.subtract(
            IndicatorMath.HUNDRED.divide(onePlusRs, IndicatorMath.MC),
        )
    }
}

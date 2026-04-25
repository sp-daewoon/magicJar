package kr.spdaewoon.magicjar.strategy.indicator

import kr.spdaewoon.magicjar.core.domain.Candle
import java.math.BigDecimal

/**
 * ADX(Average Directional Index, period=14, Wilder smoothing).
 *
 * +DM, -DM, TR을 계산 → +DI, -DI → DX → ADX(DX의 Wilder 이동평균).
 * 결과는 인덱스 2*period 부근부터 의미 있는 값. 그 이전은 null.
 */
object Adx {
    fun of(
        candles: List<Candle>,
        period: Int = 14,
    ): List<BigDecimal?> {
        require(period > 0) { "period must be positive" }
        val n = candles.size
        val out = arrayOfNulls<BigDecimal>(n)
        if (n < 2 * period + 1) return out.toList()
        val plusDm = Array(n) { BigDecimal.ZERO }
        val minusDm = Array(n) { BigDecimal.ZERO }
        val tr = Array(n) { BigDecimal.ZERO }
        for (i in 1 until n) {
            val curr = candles[i]
            val prev = candles[i - 1]
            val upMove = curr.high.subtract(prev.high)
            val downMove = prev.low.subtract(curr.low)
            plusDm[i] =
                if (upMove > downMove && upMove.signum() > 0) upMove else BigDecimal.ZERO
            minusDm[i] =
                if (downMove > upMove && downMove.signum() > 0) downMove else BigDecimal.ZERO
            val a = curr.high.subtract(curr.low)
            val b = curr.high.subtract(prev.close).abs()
            val d = curr.low.subtract(prev.close).abs()
            tr[i] = listOf(a, b, d).max()
        }
        var sumPlusDm = BigDecimal.ZERO
        var sumMinusDm = BigDecimal.ZERO
        var sumTr = BigDecimal.ZERO
        for (i in 1..period) {
            sumPlusDm = sumPlusDm.add(plusDm[i])
            sumMinusDm = sumMinusDm.add(minusDm[i])
            sumTr = sumTr.add(tr[i])
        }
        val dxList = arrayOfNulls<BigDecimal>(n)
        var smPlusDm = sumPlusDm
        var smMinusDm = sumMinusDm
        var smTr = sumTr
        dxList[period] = computeDx(smPlusDm, smMinusDm, smTr)
        for (i in period + 1 until n) {
            // Wilder smoothing on raw DM and TR
            smPlusDm =
                smPlusDm
                    .subtract(smPlusDm.divide(BigDecimal(period), IndicatorMath.MC))
                    .add(plusDm[i])
            smMinusDm =
                smMinusDm
                    .subtract(smMinusDm.divide(BigDecimal(period), IndicatorMath.MC))
                    .add(minusDm[i])
            smTr =
                smTr
                    .subtract(smTr.divide(BigDecimal(period), IndicatorMath.MC))
                    .add(tr[i])
            dxList[i] = computeDx(smPlusDm, smMinusDm, smTr)
        }
        // ADX = Wilder smoothing of DX, starting at index 2*period
        var dxSum = BigDecimal.ZERO
        for (i in period..2 * period - 1) {
            dxSum = dxSum.add(dxList[i] ?: BigDecimal.ZERO)
        }
        var adx = dxSum.divide(BigDecimal(period), IndicatorMath.MC)
        out[2 * period - 1] = adx
        val periodBd = BigDecimal(period)
        for (i in 2 * period until n) {
            val dx = dxList[i] ?: continue
            adx =
                adx
                    .multiply(BigDecimal(period - 1), IndicatorMath.MC)
                    .add(dx)
                    .divide(periodBd, IndicatorMath.MC)
            out[i] = adx
        }
        return out.toList()
    }

    private fun computeDx(
        smPlusDm: BigDecimal,
        smMinusDm: BigDecimal,
        smTr: BigDecimal,
    ): BigDecimal {
        if (smTr.signum() == 0) return BigDecimal.ZERO
        val plusDi =
            smPlusDm
                .multiply(IndicatorMath.HUNDRED, IndicatorMath.MC)
                .divide(smTr, IndicatorMath.MC)
        val minusDi =
            smMinusDm
                .multiply(IndicatorMath.HUNDRED, IndicatorMath.MC)
                .divide(smTr, IndicatorMath.MC)
        val sum = plusDi.add(minusDi)
        if (sum.signum() == 0) return BigDecimal.ZERO
        val diff = plusDi.subtract(minusDi).abs()
        return diff
            .multiply(IndicatorMath.HUNDRED, IndicatorMath.MC)
            .divide(sum, IndicatorMath.MC)
    }
}

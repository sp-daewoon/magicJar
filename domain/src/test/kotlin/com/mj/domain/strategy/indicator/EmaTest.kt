package com.mj.domain.strategy.indicator

import io.kotest.matchers.doubles.shouldBeWithinPercentageOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class EmaTest {
    @Test
    fun `EMA seeds with SMA and smooths via alpha`() {
        // 닫힘 가격 1..10. period 3 EMA 시드 = SMA(1,2,3) = 2. 이후 alpha = 0.5.
        val values = (1..10).map { BigDecimal(it) }
        val out = Ema.of(values, 3)
        check(out[0] == null)
        check(out[1] == null)
        out[2]!!.toDouble().shouldBeWithinPercentageOf(2.0, 0.001)
        // EMA[3] = alpha * 4 + (1-alpha) * 2 = 0.5*4 + 0.5*2 = 3
        out[3]!!.toDouble().shouldBeWithinPercentageOf(3.0, 0.001)
        // EMA[4] = 0.5*5 + 0.5*3 = 4
        out[4]!!.toDouble().shouldBeWithinPercentageOf(4.0, 0.001)
    }
}

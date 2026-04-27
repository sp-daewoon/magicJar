package com.mj.domain.strategy.indicator

import com.mj.domain.strategy.CandleFixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class AtrTest {
    @Test
    fun `ATR is non-negative once defined`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val atr = Atr.of(candles, 14)
        for (i in 0..13) atr[i] shouldBe null
        atr[14] shouldNotBe null
        for (v in atr) {
            if (v != null) require(v.signum() >= 0)
        }
    }
}

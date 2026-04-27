package com.mj.domain.strategy.indicator

import com.mj.domain.strategy.CandleFixtures
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class StochasticTest {
    @Test
    fun `K and D bounds 0 to 100`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val r = Stochastic.of(candles)
        for (i in candles.indices) {
            val k = r.k[i]
            if (k != null) require(k.toDouble() in 0.0..100.0)
            val d = r.d[i]
            if (d != null) require(d.toDouble() in 0.0..100.0)
        }
        // first 13 indices null for K (kPeriod=14)
        for (i in 0..12) r.k[i] shouldBe null
    }
}

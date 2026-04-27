package com.mj.domain.strategy.indicator

import com.mj.domain.strategy.CandleFixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class BollingerBandsTest {
    @Test
    fun `bands envelope the middle line`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val r = BollingerBands.ofClose(candles)
        // before 20th bar, all null
        for (i in 0..18) r.middle[i] shouldBe null
        r.middle[19] shouldNotBe null
        for (i in 19 until candles.size) {
            val mid = r.middle[i] ?: continue
            val up = r.upper[i]!!
            val lo = r.lower[i]!!
            require(up >= mid && lo <= mid) { "bands not enveloping at $i: lo=$lo mid=$mid up=$up" }
        }
    }
}

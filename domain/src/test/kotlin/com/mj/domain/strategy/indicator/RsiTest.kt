package com.mj.domain.strategy.indicator

import com.mj.domain.strategy.CandleFixtures
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import org.junit.jupiter.api.Test

class RsiTest {
    @Test
    fun `RSI is null for first period bars`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val rsi = Rsi.ofClose(candles, 14)
        for (i in 0..13) rsi[i] shouldBe null
        rsi[14] shouldNotBe null
    }

    @Test
    fun `RSI bounds 0 to 100`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val rsi = Rsi.ofClose(candles, 14)
        for (v in rsi) {
            if (v != null) {
                require(v.toDouble() in 0.0..100.0) { "RSI out of bounds: $v" }
            }
        }
    }
}

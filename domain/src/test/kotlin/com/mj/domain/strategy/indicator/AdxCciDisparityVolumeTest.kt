package com.mj.domain.strategy.indicator

import io.kotest.matchers.shouldNotBe
import com.mj.domain.strategy.CandleFixtures
import org.junit.jupiter.api.Test

class AdxCciDisparityVolumeTest {
    @Test
    fun `ADX produces non-null values past warmup`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val adx = Adx.of(candles, 14)
        adx[60] shouldNotBe null
        for (v in adx) {
            if (v != null) require(v.toDouble() in 0.0..100.0) { "ADX out of bounds: $v" }
        }
    }

    @Test
    fun `CCI past warmup`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val cci = Cci.of(candles, 20)
        cci[40] shouldNotBe null
    }

    @Test
    fun `Disparity around 100 nominally`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val disp = Disparity.of(candles, 20)
        val v = disp[40]!!
        require(v.toDouble() in 80.0..120.0)
    }

    @Test
    fun `AvgVolume defined past period`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val av = VolumeOsc.avgVolume(candles, 20)
        av[19] shouldNotBe null
    }

    @Test
    fun `VolumeOsc past slow period`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val vo = VolumeOsc.of(candles, 12, 26)
        vo[40] shouldNotBe null
    }
}

package kr.spdaewoon.magicjar.strategy.indicator

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kr.spdaewoon.magicjar.strategy.CandleFixtures
import org.junit.jupiter.api.Test

class MacdTest {
    @Test
    fun `MACD components defined where expected`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val r = Macd.ofClose(candles)
        // EMA 26 시드 위치(인덱스 25)부터 macd 정의, signal 9 만큼 더 뒤.
        r.macd[24] shouldBe null
        r.macd[25] shouldNotBe null
        r.signal[25 + 9 - 1] shouldNotBe null
        r.histogram[25 + 9 - 1] shouldNotBe null
        // histogram = macd - signal
        val i = 50
        val mi = r.macd[i]!!.toDouble()
        val si = r.signal[i]!!.toDouble()
        val hi = r.histogram[i]!!.toDouble()
        require(kotlin.math.abs((mi - si) - hi) < 0.001)
    }
}

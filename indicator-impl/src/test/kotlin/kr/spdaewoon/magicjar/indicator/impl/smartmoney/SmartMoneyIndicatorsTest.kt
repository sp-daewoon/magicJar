package kr.spdaewoon.magicjar.indicator.impl.smartmoney

import io.kotest.matchers.shouldBe
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import kotlin.time.Duration.Companion.minutes

class SmartMoneyIndicatorsTest {
    @Test
    fun `fvg detects bullish gap and exposes active zone`() {
        val candles =
            listOf(
                candle(0, 100, 105, 98, 102),
                candle(1, 102, 108, 101, 107),
                candle(2, 111, 115, 110, 114),
            )

        val result = SmartMoneyIndicators.fvg(IndicatorSpec("fvg", emptyMap()), candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        last["bullish_top"] shouldBe BigDecimal(110)
        last["bullish_bottom"] shouldBe BigDecimal(105)
        last["bullish_mid"] shouldBe BigDecimal("107.50000000")
    }

    @Test
    fun `fvg mitigation removes bullish zone`() {
        val candles =
            listOf(
                candle(0, 100, 105, 98, 102),
                candle(1, 102, 108, 101, 107),
                candle(2, 111, 115, 110, 114),
                candle(3, 109, 111, 107, 108),
            )

        val result = SmartMoneyIndicators.fvg(IndicatorSpec("fvg", mapOf("mitigationPct" to 50)), candles)
        result.series.last().values["bullish_active"] shouldBe BigDecimal.ZERO
    }

    @Test
    fun `order block detects bullish bos from last bearish candle`() {
        val candles =
            listOf(
                candle(0, 100, 101, 97, 98),
                candle(1, 98, 100, 96, 99),
                candle(2, 99, 102, 98, 101),
                candle(3, 101, 103, 100, 102),
                candle(4, 102, 110, 101, 109),
            )

        val spec = IndicatorSpec("order_block", mapOf("swingLookback" to 3, "searchLookback" to 4, "mitigationPct" to 50))
        val result = SmartMoneyIndicators.orderBlock(spec, candles)
        val last = result.series.last().values

        last["bullish_active"] shouldBe BigDecimal.ONE
        last["bos_direction"] shouldBe BigDecimal.ONE
        last["bullish_top"] shouldBe BigDecimal(101)
        last["bullish_bottom"] shouldBe BigDecimal(97)
    }

    private fun candle(
        minute: Int,
        open: Long,
        high: Long,
        low: Long,
        close: Long,
    ): Candle =
        Candle(
            id = null,
            code = "TEST",
            interval = CandleInterval.ONE_MIN,
            ts = Instant.parse("2026-05-30T00:00:00Z").plus(minute.minutes),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(high),
            lowRaw = BigDecimal(low),
            closeRaw = BigDecimal(close),
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "TEST",
        )
}

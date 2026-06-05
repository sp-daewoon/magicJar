package kr.spdaewoon.magicjar.domain.strategyresearch

import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.datetime.Instant
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class StrategyResearchEngineTest {
    @Test
    fun `volume washout candidate uses next candle open and reports a take profit trade`() {
        val engine = StrategyResearchEngine()
        val report =
            engine.research(
                request = request(targetProfitPct = 1.0, stopLossPct = 0.8, maxHoldBars = 4),
                candles = washoutCandles(),
            )

        val candidate = report.candidates.first { it.key == "VOLUME_WASHOUT_REBOUND" }
        candidate.trades.shouldNotBeEmpty()
        val trade = candidate.trades.first()
        trade.signalTime shouldBe t("2026-04-28T00:26:00Z")
        trade.entryTime shouldBe t("2026-04-28T00:27:00Z")
        trade.exitReason shouldBe StrategyResearchExitReason.TAKE_PROFIT
        trade.returnPct.shouldBeGreaterThan(0.0)
        candidate.stats.totalReturnPct.shouldBeGreaterThan(0.0)
        candidate.riskRules.joinToString(" ") shouldContain "다음 candle 시가"
    }

    @Test
    fun `insufficient candles returns warnings without candidates`() {
        val engine = StrategyResearchEngine()

        val report = engine.research(request(), washoutCandles().take(10))

        report.candidates shouldBe emptyList()
        report.warnings.joinToString(" ") shouldContain "최소"
    }

    private fun request(
        targetProfitPct: Double = 1.5,
        stopLossPct: Double = 0.8,
        maxHoldBars: Int = 12,
    ): StrategyResearchRequest =
        StrategyResearchRequest(
            userId = "74683223",
            code = "038110",
            interval = "1m",
            from = t("2026-04-28T00:00:00Z"),
            to = t("2026-04-28T00:44:00Z"),
            targetProfitPct = targetProfitPct,
            stopLossPct = stopLossPct,
            maxHoldBars = maxHoldBars,
        )

    private fun washoutCandles(): List<ResearchCandle> {
        val candles = mutableListOf<ResearchCandle>()
        repeat(45) { index ->
            val ts = t("2026-04-28T00:${index.toString().padStart(2, '0')}:00Z")
            val open: String
            val high: String
            val low: String
            val close: String
            val volume: Long
            when (index) {
                25 -> {
                    open = "100.0"
                    high = "100.2"
                    low = "97.8"
                    close = "98.0"
                    volume = 3000
                }
                26 -> {
                    open = "98.0"
                    high = "99.3"
                    low = "97.9"
                    close = "99.0"
                    volume = 1800
                }
                27 -> {
                    open = "99.0"
                    high = "100.5"
                    low = "98.8"
                    close = "100.1"
                    volume = 1600
                }
                else -> {
                    open = "100.0"
                    high = "100.4"
                    low = "99.6"
                    close = "100.0"
                    volume = 1000
                }
            }
            candles += candle(ts, open, high, low, close, volume)
        }
        return candles
    }

    private fun candle(
        ts: Instant,
        open: String,
        high: String,
        low: String,
        close: String,
        volume: Long,
    ): ResearchCandle =
        ResearchCandle(
            code = "038110",
            interval = "1m",
            ts = ts,
            open = bd(open),
            high = bd(high),
            low = bd(low),
            close = bd(close),
            volume = volume,
        )

    private fun t(value: String): Instant = Instant.parse(value)

    private fun bd(value: String): BigDecimal = BigDecimal(value)
}

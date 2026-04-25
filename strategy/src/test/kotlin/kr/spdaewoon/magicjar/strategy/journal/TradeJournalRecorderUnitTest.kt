package kr.spdaewoon.magicjar.strategy.journal

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class TradeJournalRecorderUnitTest {
    @Test
    fun `entry then exit computes pnl`() {
        val jpa = mockk<TradeJournalJpaRepository>()
        val catalog = mockk<InstrumentCatalog>()
        val publisher = mockk<ApplicationEventPublisher>(relaxed = true)
        every { catalog.findByCode(any()) } returns null
        // entry: no existing
        every {
            jpa.findFirstByCodeAndModeAndExitAtIsNullOrderByEntryAtDesc("005930", Mode.BACKTEST)
        } returns null
        val savedEntry = slot<TradeJournalJpa>()
        every { jpa.save(capture(savedEntry)) } answers { firstArg<TradeJournalJpa>() }

        val clock = Clock.fixed(Instant.parse("2024-06-01T00:00:00Z"), ZoneOffset.UTC)
        val recorder =
            TradeJournalRecorder(
                jpa = jpa,
                instrumentCatalog = catalog,
                publisher = publisher,
                aiReviewEnabled = false,
                clock = clock,
            )
        val id =
            recorder.recordEntry(
                mode = Mode.BACKTEST,
                code = "005930",
                name = "삼성전자",
                qty = 10,
                price = BigDecimal("70000"),
                at = Instant.parse("2024-01-10T00:00:00Z"),
                strategyId = "rsi_oversold",
                entryReason = "RSI<30",
            )
        require(id == savedEntry.captured.id)
        savedEntry.captured.entryQty shouldBe 10
        savedEntry.captured.entryPrice shouldBe BigDecimal("70000")

        // now stub exit lookup to return the saved entry
        every {
            jpa.findFirstByCodeAndModeAndExitAtIsNullOrderByEntryAtDesc("005930", Mode.BACKTEST)
        } returns savedEntry.captured

        recorder.recordExit(
            mode = Mode.BACKTEST,
            code = "005930",
            qty = 10,
            price = BigDecimal("75000"),
            at = Instant.parse("2024-02-10T00:00:00Z"),
            exitReason = "TP",
        )
        // pnl = (75000 - 70000) * 10 = 50000
        savedEntry.captured.pnl!!.toDouble() shouldBe 50000.0
        // pnlRate = 5000/70000 ~ 0.0714286
        require(kotlin.math.abs(savedEntry.captured.pnlRate!!.toDouble() - 0.0714286) < 0.0001)
    }
}

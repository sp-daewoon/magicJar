package com.mj.consumer.infrastructure.persistence

import com.mj.consumer.infrastructure.persistence.journal.TradeJournalJpa
import com.mj.consumer.infrastructure.persistence.journal.TradeJournalJpaRepository
import com.mj.consumer.infrastructure.persistence.journal.TradeJournalRepositoryAdapter
import com.mj.domain.common.Mode
import com.mj.domain.common.Side
import com.mj.domain.journal.TradeJournalEntry
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.Optional
import java.util.UUID

class TradeJournalRepositoryAdapterTest {
    private val jpa = mockk<TradeJournalJpaRepository>()
    private val adapter = TradeJournalRepositoryAdapter(jpa)

    private fun sampleEntry(): TradeJournalEntry {
        val id = UUID.randomUUID()
        return TradeJournalEntry(
            id = id,
            tradeId = id,
            mode = Mode.BACKTEST,
            code = "005930",
            name = "삼성전자",
            side = Side.BUY,
            entryAt = Instant.now(),
            entryPrice = BigDecimal("70000"),
            entryQty = 10,
            strategyId = "sma-cross",
            entrySnapshot = emptyMap(),
            entryReason = "SMA cross",
            createdAt = Instant.now(),
            updatedAt = Instant.now(),
        )
    }

    @Test
    fun `save converts domain to jpa and persists`() {
        val entry = sampleEntry()
        val savedSlot = slot<TradeJournalJpa>()
        every { jpa.save(capture(savedSlot)) } answers { savedSlot.captured }

        adapter.save(entry)

        verify(exactly = 1) { jpa.save(any()) }
        assertEquals(entry.id, savedSlot.captured.id)
        assertEquals(entry.code, savedSlot.captured.code)
    }

    @Test
    fun `find returns null for unknown id`() {
        val id = UUID.randomUUID()
        every { jpa.findById(id) } returns Optional.empty()

        val result = adapter.find(id)

        assertEquals(null, result)
    }
}

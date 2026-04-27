package com.mj.api.infrastructure.persistence

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import com.mj.api.infrastructure.persistence.snapshot.ChartSnapshotAdapter
import com.mj.api.infrastructure.persistence.snapshot.ChartSnapshotJpaRepository
import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.SnapshotSource
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ChartSnapshotAdapterTest {
    private val jpa = mockk<ChartSnapshotJpaRepository>()
    private val mapper = ObjectMapper().registerModule(JavaTimeModule()).registerModule(kotlinModule())
    private val adapter = ChartSnapshotAdapter(jpa, mapper)

    private fun sampleSnapshot(tradeId: UUID = UUID.randomUUID()): ChartSnapshot =
        ChartSnapshot(
            id = UUID.randomUUID(),
            tradeId = tradeId,
            symbol = "005930",
            timeframe = "1m",
            capturedAt = Instant.now(),
            source = SnapshotSource.CLIENT_LWC,
            storageKey = "snapshots/test/img.png",
            publicUrl = "http://minio/test/img.png",
        )

    @Test
    fun `save converts domain to jpa and persists`() {
        val snapshot = sampleSnapshot()
        val savedSlot = slot<com.mj.api.infrastructure.persistence.snapshot.ChartSnapshotJpa>()
        every { jpa.save(capture(savedSlot)) } answers { savedSlot.captured }

        adapter.save(snapshot)

        verify(exactly = 1) { jpa.save(any()) }
        assertEquals(snapshot.id, savedSlot.captured.id)
        assertEquals(snapshot.tradeId, savedSlot.captured.tradeId)
    }

    @Test
    fun `findByTradeId returns list of snapshots`() {
        val tradeId = UUID.randomUUID()
        val snapshot = sampleSnapshot(tradeId)
        val jpaEntity =
            snapshot.let {
                com.mj.api.infrastructure.persistence.snapshot.ChartSnapshotJpa(
                    id = it.id,
                    tradeId = it.tradeId,
                    symbol = it.symbol,
                    timeframe = it.timeframe,
                    capturedAt = it.capturedAt,
                    source = it.source,
                    storageKey = it.storageKey,
                    publicUrl = it.publicUrl,
                    metaJson = "{}",
                )
            }
        every { jpa.findAllByTradeId(tradeId) } returns listOf(jpaEntity)

        val result = adapter.findByTradeId(tradeId)

        assertEquals(1, result.size)
        assertEquals(tradeId, result[0].tradeId)
    }

    @Test
    fun `findById returns null for unknown id`() {
        val id = UUID.randomUUID()
        every { jpa.findById(id) } returns Optional.empty()

        val result = adapter.findById(id)

        assertEquals(null, result)
    }
}

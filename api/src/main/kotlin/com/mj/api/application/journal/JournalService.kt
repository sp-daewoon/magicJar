package com.mj.api.application.journal

import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.SnapshotSource
import com.mj.domain.journal.TradeJournalEntry
import com.mj.domain.journal.port.ChartSnapshotRepository
import com.mj.domain.journal.port.TradeJournalFilter
import com.mj.domain.journal.port.TradeJournalRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

@Service
class JournalService(
    private val journalRepository: TradeJournalRepository,
    private val snapshotRepository: ChartSnapshotRepository,
) {
    private val log = LoggerFactory.getLogger(JournalService::class.java)

    @Transactional(readOnly = true)
    fun list(filter: TradeJournalFilter): List<TradeJournalEntry> = journalRepository.search(filter)

    @Transactional(readOnly = true)
    fun get(tradeId: UUID): TradeJournalEntry? = journalRepository.find(tradeId)

    @Transactional
    fun saveSnapshot(
        tradeId: UUID,
        imageBase64: String,
        contentType: String,
        width: Int,
        height: Int,
        source: SnapshotSource = SnapshotSource.CLIENT_LWC,
    ): ChartSnapshot {
        // Phase E1: storageKey = tradeId/uuid (MinIO 연동은 Phase E2+)
        val snapId = UUID.randomUUID()
        val storageKey = "snapshots/$tradeId/$snapId.png"
        val snapshot =
            ChartSnapshot(
                id = snapId,
                tradeId = tradeId,
                symbol = "", // 클라이언트에서 전달되면 향후 확장
                timeframe = "",
                capturedAt = Instant.now(),
                source = source,
                storageKey = storageKey,
                publicUrl = "/api/journal/snapshots/$snapId", // Phase E2 MinIO presigned URL 교체
                meta = mapOf("width" to "$width", "height" to "$height", "contentType" to contentType),
            )
        snapshotRepository.save(snapshot)
        log.info("ChartSnapshot saved: id={} tradeId={}", snapId, tradeId)
        return snapshot
    }

    @Transactional(readOnly = true)
    fun listSnapshots(tradeId: UUID): List<ChartSnapshot> = snapshotRepository.findByTradeId(tradeId)
}

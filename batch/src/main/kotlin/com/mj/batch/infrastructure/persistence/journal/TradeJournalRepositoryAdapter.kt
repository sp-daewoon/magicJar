package com.mj.batch.infrastructure.persistence.journal

import com.mj.domain.journal.TradeJournalEntry
import com.mj.domain.journal.port.TradeJournalFilter
import com.mj.domain.journal.port.TradeJournalRepository
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Primary
@Transactional
class TradeJournalRepositoryAdapter(
    private val jpa: TradeJournalJpaRepository,
) : TradeJournalRepository {
    override fun save(entry: TradeJournalEntry) {
        jpa.save(entry.toJpa())
    }

    @Transactional(readOnly = true)
    override fun find(id: UUID): TradeJournalEntry? = jpa.findById(id).orElse(null)?.toDomain()

    @Transactional(readOnly = true)
    override fun search(filter: TradeJournalFilter): List<TradeJournalEntry> =
        jpa
            .findAll()
            .filter { entry ->
                (filter.mode == null || entry.mode == filter.mode) &&
                    (filter.code == null || entry.code == filter.code) &&
                    (filter.side == null || entry.side == filter.side) &&
                    (filter.strategyId == null || entry.strategyId == filter.strategyId) &&
                    entry.deletedAt == null
            }.drop(filter.offset)
            .take(filter.limit)
            .map { it.toDomain() }
}

internal fun TradeJournalEntry.toJpa(): TradeJournalJpa =
    TradeJournalJpa(
        id = id,
        tradeId = tradeId,
        mode = mode,
        code = code,
        name = name,
        side = side,
        entryAt = entryAt,
        entryPrice = entryPrice,
        entryQty = entryQty,
        exitAt = exitAt,
        exitPrice = exitPrice,
        exitQty = exitQty,
        pnl = pnl,
        pnlRate = pnlRate,
        strategyId = strategyId,
        entrySnapshot = JournalJsonCodec.encodeSnapshot(entrySnapshot),
        exitSnapshot = JournalJsonCodec.encodeSnapshot(exitSnapshot),
        entryReason = entryReason,
        exitReason = exitReason,
        aiExplanation = aiExplanation,
        aiReview = aiReview,
        tags = JournalJsonCodec.encodeTags(tags),
        userNote = userNote,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

internal fun TradeJournalJpa.toDomain(): TradeJournalEntry =
    TradeJournalEntry(
        id = id,
        tradeId = tradeId,
        mode = mode,
        code = code,
        name = name,
        side = side,
        entryAt = entryAt,
        entryPrice = entryPrice,
        entryQty = entryQty,
        exitAt = exitAt,
        exitPrice = exitPrice,
        exitQty = exitQty,
        pnl = pnl,
        pnlRate = pnlRate,
        strategyId = strategyId,
        entrySnapshot = JournalJsonCodec.decodeSnapshot(entrySnapshot),
        exitSnapshot = JournalJsonCodec.decodeSnapshot(exitSnapshot),
        entryReason = entryReason,
        exitReason = exitReason,
        aiExplanation = aiExplanation,
        aiReview = aiReview,
        tags = JournalJsonCodec.decodeTags(tags),
        userNote = userNote,
        createdAt = createdAt,
        updatedAt = updatedAt,
        deletedAt = deletedAt,
    )

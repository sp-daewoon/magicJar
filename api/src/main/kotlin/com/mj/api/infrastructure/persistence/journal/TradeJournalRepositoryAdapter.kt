package com.mj.api.infrastructure.persistence.journal

import com.mj.domain.journal.TradeJournalEntry
import com.mj.domain.journal.port.TradeJournalFilter
import com.mj.domain.journal.port.TradeJournalRepository
import jakarta.persistence.criteria.Predicate
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * domain.journal.port.TradeJournalRepository 구현. JPA + Specification으로 동적 검색을 제공한다.
 */
@Component
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
    override fun search(filter: TradeJournalFilter): List<TradeJournalEntry> {
        val spec = buildSpec(filter)
        val page =
            PageRequest.of(
                filter.offset / filter.limit.coerceAtLeast(1),
                filter.limit.coerceAtLeast(1),
                Sort.by(Sort.Direction.DESC, "entryAt"),
            )
        return jpa.findAll(spec, page).content.map { it.toDomain() }
    }

    private fun buildSpec(filter: TradeJournalFilter): Specification<TradeJournalJpa> =
        Specification { root, _, cb ->
            val predicates = mutableListOf<Predicate>()
            filter.mode?.let { predicates += cb.equal(root.get<Any>("mode"), it) }
            filter.code?.let { predicates += cb.equal(root.get<String>("code"), it) }
            filter.side?.let { predicates += cb.equal(root.get<Any>("side"), it) }
            filter.strategyId?.let { predicates += cb.equal(root.get<String>("strategyId"), it) }
            filter.from?.let { predicates += cb.greaterThanOrEqualTo(root.get("entryAt"), it) }
            filter.to?.let { predicates += cb.lessThanOrEqualTo(root.get("entryAt"), it) }
            filter.tag?.let {
                predicates += cb.like(root.get<String>("tags"), "%\"$it\"%")
            }
            predicates += cb.isNull(root.get<Any>("deletedAt"))
            cb.and(*predicates.toTypedArray())
        }
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

package com.mj.api.infrastructure.persistence.snapshot

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.domain.journal.ChartSnapshot
import com.mj.domain.journal.port.ChartSnapshotRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Component
@Transactional
class ChartSnapshotAdapter(
    private val jpa: ChartSnapshotJpaRepository,
    private val mapper: ObjectMapper,
) : ChartSnapshotRepository {
    override fun save(snapshot: ChartSnapshot) {
        jpa.save(snapshot.toJpa(mapper))
    }

    @Transactional(readOnly = true)
    override fun findByTradeId(tradeId: UUID): List<ChartSnapshot> = jpa.findAllByTradeId(tradeId).map { it.toDomain(mapper) }

    @Transactional(readOnly = true)
    override fun findById(id: UUID): ChartSnapshot? = jpa.findById(id).orElse(null)?.toDomain(mapper)
}

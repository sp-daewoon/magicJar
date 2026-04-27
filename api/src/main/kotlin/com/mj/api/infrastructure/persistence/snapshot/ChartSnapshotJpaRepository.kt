package com.mj.api.infrastructure.persistence.snapshot

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ChartSnapshotJpaRepository : JpaRepository<ChartSnapshotJpa, UUID> {
    fun findAllByTradeId(tradeId: UUID): List<ChartSnapshotJpa>
}

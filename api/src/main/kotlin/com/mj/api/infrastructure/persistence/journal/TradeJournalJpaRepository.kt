package com.mj.api.infrastructure.persistence.journal

import com.mj.domain.common.Mode
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface TradeJournalJpaRepository :
    JpaRepository<TradeJournalJpa, UUID>,
    JpaSpecificationExecutor<TradeJournalJpa> {
    fun findFirstByTradeIdAndExitAtIsNull(tradeId: UUID): TradeJournalJpa?

    fun findFirstByCodeAndModeAndExitAtIsNullOrderByEntryAtDesc(
        code: String,
        mode: Mode,
    ): TradeJournalJpa?
}

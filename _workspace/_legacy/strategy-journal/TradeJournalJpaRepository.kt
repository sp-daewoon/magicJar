package com.mj.domain.strategy.journal

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface TradeJournalJpaRepository :
    JpaRepository<TradeJournalJpa, UUID>,
    JpaSpecificationExecutor<TradeJournalJpa> {
    fun findFirstByTradeIdAndExitAtIsNull(tradeId: UUID): TradeJournalJpa?

    fun findFirstByCodeAndModeAndExitAtIsNullOrderByEntryAtDesc(
        code: String,
        mode: kr.spdaewoon.magicjar.core.domain.Mode,
    ): TradeJournalJpa?
}

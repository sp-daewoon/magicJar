package com.mj.batch.infrastructure.persistence.journal

import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface TradeJournalJpaRepository : JpaRepository<TradeJournalJpa, UUID>

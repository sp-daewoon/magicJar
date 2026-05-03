package com.mj.batch.infrastructure.persistence.backtest

import org.springframework.data.jpa.repository.JpaRepository

interface BacktestEquityPointJpaRepository : JpaRepository<BacktestEquityPointJpa, BacktestEquityPointId> {
    fun deleteByIdRunId(runId: String)
}

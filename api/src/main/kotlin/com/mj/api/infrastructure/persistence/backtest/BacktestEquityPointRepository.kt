package com.mj.api.infrastructure.persistence.backtest

import org.springframework.data.jpa.repository.JpaRepository

interface BacktestEquityPointRepository : JpaRepository<BacktestEquityPointJpa, BacktestEquityPointId> {
    fun findByIdRunIdOrderByIdSequenceAsc(runId: String): List<BacktestEquityPointJpa>

    fun countByIdRunId(runId: String): Long
}

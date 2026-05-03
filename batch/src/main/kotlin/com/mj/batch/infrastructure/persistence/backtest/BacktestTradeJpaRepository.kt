package com.mj.batch.infrastructure.persistence.backtest

import org.springframework.data.jpa.repository.JpaRepository

interface BacktestTradeJpaRepository : JpaRepository<BacktestTradeJpa, String> {
    fun deleteByRunId(runId: String)
}

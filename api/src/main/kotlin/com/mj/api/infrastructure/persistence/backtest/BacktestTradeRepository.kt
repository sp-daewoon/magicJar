package com.mj.api.infrastructure.persistence.backtest

import org.springframework.data.jpa.repository.JpaRepository

interface BacktestTradeRepository : JpaRepository<BacktestTradeJpa, String> {
    fun findByRunIdOrderBySequenceAsc(runId: String): List<BacktestTradeJpa>

    fun countByRunId(runId: String): Long
}

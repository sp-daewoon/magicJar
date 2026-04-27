package com.mj.api.infrastructure.persistence.backtest

import org.springframework.data.jpa.repository.JpaRepository

interface BacktestRunRepository : JpaRepository<BacktestRunJpa, String> {
    fun findByRunId(runId: String): BacktestRunJpa?
}

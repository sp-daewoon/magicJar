package com.mj.batch.infrastructure.persistence.backtest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/** batch 전용 BacktestRun 엔티티 — 잡 실행 후 status/summary 업데이트. */
@Entity
@Table(name = "backtest_run")
class BacktestRunJpa(
    @Id @Column(name = "run_id", length = 64) var runId: String,
    @Column(name = "strategy_id", length = 64) var strategyId: String,
    @Column(name = "symbol", length = 16) var symbol: String,
    @Column(name = "from_date", length = 16) var fromDate: String,
    @Column(name = "to_date", length = 16) var toDate: String,
    @Column(name = "status", length = 16) var status: String,
    @Column(name = "started_at") var startedAt: Instant?,
    @Column(name = "finished_at") var finishedAt: Instant?,
    @Column(name = "summary_json", columnDefinition = "text") var summaryJson: String?,
    @Column(name = "error_message", columnDefinition = "text") var errorMessage: String?,
    @Column(name = "created_at") var createdAt: Instant,
)

package com.mj.api.infrastructure.persistence.backtest

import com.mj.api.application.backtest.BacktestRunStatus
import com.mj.api.application.backtest.BacktestStatusResponse
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.Instant

/**
 * 백테스트 실행 메타데이터 영속 엔티티.
 * api-app이 runId 발급 + 상태 추적. batch-app이 실행 후 상태 업데이트.
 */
@Entity
@Table(
    name = "backtest_run",
    indexes = [
        Index(name = "idx_backtest_run_strategy_id", columnList = "strategy_id"),
    ],
)
class BacktestRunJpa(
    @Id
    @Column(name = "run_id", length = 64, nullable = false)
    var runId: String,
    @Column(name = "strategy_id", length = 64, nullable = false)
    var strategyId: String,
    @Column(name = "symbol", length = 16, nullable = false)
    var symbol: String,
    @Column(name = "from_date", length = 16, nullable = false)
    var fromDate: String,
    @Column(name = "to_date", length = 16, nullable = false)
    var toDate: String,
    @Column(name = "status", length = 16, nullable = false)
    var status: String,
    @Column(name = "started_at")
    var startedAt: Instant?,
    @Column(name = "finished_at")
    var finishedAt: Instant?,
    @Column(name = "summary_json", columnDefinition = "text")
    var summaryJson: String?,
    @Column(name = "error_message", columnDefinition = "text")
    var errorMessage: String?,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)

fun BacktestRunJpa.toDto(): BacktestStatusResponse =
    BacktestStatusResponse(
        runId = runId,
        strategyId = strategyId,
        symbol = symbol,
        from = fromDate,
        to = toDate,
        status = runCatching { BacktestRunStatus.valueOf(status) }.getOrElse { BacktestRunStatus.QUEUED },
        startedAt = startedAt?.toString(),
        finishedAt = finishedAt?.toString(),
        summaryJson = summaryJson,
        errorMessage = errorMessage,
    )

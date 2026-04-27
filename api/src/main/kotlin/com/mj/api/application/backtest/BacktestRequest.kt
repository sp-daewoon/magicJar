package com.mj.api.application.backtest

import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern

data class BacktestTriggerRequest(
    @field:NotBlank val strategyId: String,
    @field:NotBlank val symbol: String,
    @field:Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") val from: String,
    @field:Pattern(regexp = "\\d{4}-\\d{2}-\\d{2}") val to: String,
)

data class BacktestTriggerResponse(
    val runId: String,
    val status: BacktestRunStatus,
    val message: String,
)

data class BacktestStatusResponse(
    val runId: String,
    val strategyId: String,
    val symbol: String,
    val from: String,
    val to: String,
    val status: BacktestRunStatus,
    val startedAt: String?,
    val finishedAt: String?,
    val summaryJson: String?,
    val errorMessage: String?,
)

enum class BacktestRunStatus { QUEUED, RUNNING, COMPLETED, FAILED }

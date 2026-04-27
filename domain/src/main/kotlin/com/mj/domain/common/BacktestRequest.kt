package com.mj.domain.common

import java.time.LocalDate

data class BacktestRequest(
    val strategy: StrategyRef,
    // 조건식 DSL
    val conditionExpr: String,
    val interval: Interval,
    val from: LocalDate,
    val to: LocalDate,
    val universe: List<String>,
    val config: BacktestConfig,
)

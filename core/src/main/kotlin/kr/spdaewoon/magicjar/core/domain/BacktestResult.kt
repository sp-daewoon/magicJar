package kr.spdaewoon.magicjar.core.domain

import java.time.LocalDate

data class BacktestResult(
    val strategy: StrategyRef,
    val from: LocalDate,
    val to: LocalDate,
    val metrics: Metrics,
    val trades: List<TradeRef>,
    val aiExplanation: String? = null,
)

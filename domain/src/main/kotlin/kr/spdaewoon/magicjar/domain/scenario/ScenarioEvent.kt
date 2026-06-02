package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Instant

enum class ScenarioEventType {
    CANDLE,
    SIGNAL_BUY,
    SIGNAL_SELL,
    SIGNAL_HOLD,
    RISK_ALLOWED,
    RISK_BLOCKED,
    ORDER_REQUESTED,
    ORDER_HELD,
    ORDER_REJECTED,
    ORDER_FILLED,
    POSITION_UPDATED,
    DATA_NOT_READY,
    STRATEGY_ERROR,
    FAULT_INJECTED,
}

enum class ScenarioSeverity {
    INFO,
    WARN,
    ERROR,
}

data class ScenarioEvent(
    val virtualTime: Instant,
    val type: ScenarioEventType,
    val severity: ScenarioSeverity,
    val title: String,
    val message: String,
    val strategyId: Long?,
    val strategyName: String?,
    val code: String?,
    val payload: Map<String, Any?> = emptyMap(),
) {
    init {
        require(title.isNotBlank()) { "title must be non-blank" }
        require(message.isNotBlank()) { "message must be non-blank" }
    }
}

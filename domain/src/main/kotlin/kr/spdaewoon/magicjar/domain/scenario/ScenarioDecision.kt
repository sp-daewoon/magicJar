package kr.spdaewoon.magicjar.domain.scenario

import java.math.BigDecimal

enum class ScenarioSignalSide {
    BUY,
    SELL,
    HOLD,
}

enum class ScenarioOrderPriceType {
    MARKET,
    LIMIT,
}

data class ScenarioSignalDecision(
    val side: ScenarioSignalSide,
    val quantity: Long? = null,
    val priceType: ScenarioOrderPriceType = ScenarioOrderPriceType.MARKET,
    val limitPrice: BigDecimal? = null,
    val reason: String? = null,
) {
    init {
        require(quantity == null || quantity > 0) { "quantity must be positive when present: $quantity" }
        require(limitPrice == null || limitPrice > BigDecimal.ZERO) { "limitPrice must be positive when present: $limitPrice" }
        require(priceType == ScenarioOrderPriceType.MARKET || limitPrice != null) {
            "limitPrice is required for LIMIT scenario orders"
        }
    }

    companion object {
        fun hold(reason: String? = null): ScenarioSignalDecision = ScenarioSignalDecision(side = ScenarioSignalSide.HOLD, reason = reason)

        fun buy(
            quantity: Long? = null,
            reason: String? = null,
        ): ScenarioSignalDecision =
            ScenarioSignalDecision(
                side = ScenarioSignalSide.BUY,
                quantity = quantity,
                reason = reason,
            )

        fun sell(
            quantity: Long? = null,
            reason: String? = null,
        ): ScenarioSignalDecision =
            ScenarioSignalDecision(
                side = ScenarioSignalSide.SELL,
                quantity = quantity,
                reason = reason,
            )
    }
}

sealed class ScenarioRiskDecision {
    object Allowed : ScenarioRiskDecision()

    data class Blocked(
        val reasonCode: String,
        val message: String,
        val payload: Map<String, Any?> = emptyMap(),
    ) : ScenarioRiskDecision() {
        init {
            require(reasonCode.isNotBlank()) { "reasonCode must be non-blank" }
            require(message.isNotBlank()) { "message must be non-blank" }
        }
    }
}

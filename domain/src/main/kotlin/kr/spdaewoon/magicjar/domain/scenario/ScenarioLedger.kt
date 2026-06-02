package kr.spdaewoon.magicjar.domain.scenario

import kotlinx.datetime.Instant
import java.math.BigDecimal
import java.math.RoundingMode

data class ScenarioPositionSeed(
    val code: String,
    val quantity: Long,
    val averagePrice: BigDecimal,
) {
    init {
        require(code.matches(CODE_PATTERN)) { "code must be 6 digits: $code" }
        require(quantity >= 0) { "quantity must be >= 0: $quantity" }
        require(averagePrice >= BigDecimal.ZERO) { "averagePrice must be >= 0: $averagePrice" }
    }

    companion object {
        private val CODE_PATTERN = Regex("\\d{6}")
    }
}

data class ScenarioPositionSnapshot(
    val code: String,
    val quantity: Long,
    val averagePrice: BigDecimal,
    val updatedAt: Instant,
)

data class ScenarioLedgerSnapshot(
    val cash: BigDecimal,
    val positions: List<ScenarioPositionSnapshot>,
)

class ScenarioLedger(
    initialCash: BigDecimal,
    initialPositions: List<ScenarioPositionSeed>,
) {
    private var cash: BigDecimal = initialCash
    private val positions: MutableMap<String, MutablePosition> =
        initialPositions
            .filter { it.quantity > 0 }
            .associate { seed -> seed.code to MutablePosition(seed.quantity, seed.averagePrice) }
            .toMutableMap()

    init {
        require(initialCash >= BigDecimal.ZERO) { "initialCash must be >= 0: $initialCash" }
    }

    fun cash(): BigDecimal = cash

    fun availableQuantity(code: String): Long = positions[code]?.quantity ?: 0L

    fun maxAffordableQuantity(price: BigDecimal): Long {
        require(price > BigDecimal.ZERO) { "price must be positive: $price" }
        return cash.divide(price, 0, RoundingMode.DOWN).toLong()
    }

    fun buy(
        code: String,
        quantity: Long,
        price: BigDecimal,
        at: Instant,
    ): ScenarioPositionSnapshot {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(price > BigDecimal.ZERO) { "price must be positive: $price" }
        val cost = price.multiply(BigDecimal.valueOf(quantity))
        require(cash >= cost) { "scenario cash is insufficient: cash=$cash, cost=$cost" }

        val current = positions[code]
        val next =
            if (current == null) {
                MutablePosition(quantity = quantity, averagePrice = price)
            } else {
                val currentCost = current.averagePrice.multiply(BigDecimal.valueOf(current.quantity))
                val nextQuantity = current.quantity + quantity
                val nextAverage =
                    currentCost
                        .add(cost)
                        .divide(BigDecimal.valueOf(nextQuantity), 6, RoundingMode.HALF_UP)
                MutablePosition(quantity = nextQuantity, averagePrice = nextAverage)
            }
        positions[code] = next
        cash = cash.subtract(cost)
        return next.toSnapshot(code, at)
    }

    fun sell(
        code: String,
        quantity: Long,
        price: BigDecimal,
        at: Instant,
    ): ScenarioPositionSnapshot {
        require(quantity > 0) { "quantity must be positive: $quantity" }
        require(price > BigDecimal.ZERO) { "price must be positive: $price" }
        val current = positions[code]
        require(current != null && current.quantity >= quantity) {
            "scenario position quantity is insufficient: code=$code, holding=${current?.quantity ?: 0}, requested=$quantity"
        }

        val nextQuantity = current.quantity - quantity
        cash = cash.add(price.multiply(BigDecimal.valueOf(quantity)))
        return if (nextQuantity == 0L) {
            positions.remove(code)
            ScenarioPositionSnapshot(code = code, quantity = 0, averagePrice = BigDecimal.ZERO, updatedAt = at)
        } else {
            val next = MutablePosition(quantity = nextQuantity, averagePrice = current.averagePrice)
            positions[code] = next
            next.toSnapshot(code, at)
        }
    }

    fun snapshot(at: Instant): ScenarioLedgerSnapshot =
        ScenarioLedgerSnapshot(
            cash = cash,
            positions = positions.map { (code, position) -> position.toSnapshot(code, at) }.sortedBy { it.code },
        )

    private data class MutablePosition(
        val quantity: Long,
        val averagePrice: BigDecimal,
    ) {
        fun toSnapshot(
            code: String,
            at: Instant,
        ): ScenarioPositionSnapshot =
            ScenarioPositionSnapshot(
                code = code,
                quantity = quantity,
                averagePrice = averagePrice,
                updatedAt = at,
            )
    }
}

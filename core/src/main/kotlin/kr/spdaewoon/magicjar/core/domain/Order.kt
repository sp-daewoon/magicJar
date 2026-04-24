package kr.spdaewoon.magicjar.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class Order(
    val id: UUID,
    val code: String,
    val side: Side,
    val qty: Int,
    // LIMIT에서만 의미. MARKET 주문은 null.
    val price: BigDecimal?,
    val type: OrderType,
    val status: OrderStatus,
    val createdAt: Instant,
)

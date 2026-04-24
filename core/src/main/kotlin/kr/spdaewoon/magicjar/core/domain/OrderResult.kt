package kr.spdaewoon.magicjar.core.domain

import java.math.BigDecimal
import java.time.Instant

data class OrderResult(
    val order: Order,
    val resultStatus: OrderStatus,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    val filledAt: Instant?,
    val rejectReason: String? = null,
)

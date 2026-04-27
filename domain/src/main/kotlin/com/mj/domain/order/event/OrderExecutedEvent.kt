package com.mj.domain.order.event

import com.mj.domain.common.OrderStatus
import com.mj.domain.common.Side
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Kafka 토픽 order.executed.v1 메시지 스키마 — KIS 체결 결과 */
data class OrderExecutedEvent(
    val orderId: UUID,
    val intentId: UUID,
    val code: String,
    val side: Side,
    val executedQty: Int,
    val executedPrice: BigDecimal,
    val status: OrderStatus,
    val executedAt: Instant,
    val mode: String,
)

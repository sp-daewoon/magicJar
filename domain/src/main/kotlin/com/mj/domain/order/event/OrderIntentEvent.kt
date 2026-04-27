package com.mj.domain.order.event

import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Kafka 토픽 order.intent.v1 메시지 스키마 — 전략 신호로부터 생성된 주문 의도 */
data class OrderIntentEvent(
    val intentId: UUID,
    val strategyId: String,
    val signalId: UUID,
    val code: String,
    val side: Side,
    val qty: Int,
    val price: BigDecimal?,
    val orderType: OrderType,
    val mode: String,
    val createdAt: Instant,
)

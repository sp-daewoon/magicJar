package com.mj.api.application.order

import com.mj.api.controller.dto.OrderRequest
import com.mj.api.controller.dto.OrderResponse
import com.mj.api.controller.dto.OrderTriggerResponse
import com.mj.api.controller.dto.toResponse
import com.mj.api.infrastructure.persistence.order.OrderApiJpaRepository
import com.mj.api.messaging.producer.OrderIntentApiPublisher
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.event.OrderIntentEvent
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID

@Service
class OrderApiService(
    private val repo: OrderApiJpaRepository,
    private val publisher: OrderIntentApiPublisher,
) {
    /** 사용자별 최근 100건 (모든 상태). */
    fun listOrders(userId: String): List<OrderResponse> = repo.findTop100ByUserIdOrderByCreatedAtDesc(userId).map { it.toResponse() }

    /** 미체결만 (PENDING + PARTIALLY_FILLED). */
    fun listOpenOrders(userId: String): List<OrderResponse> =
        repo
            .findByUserIdAndStatusInOrderByCreatedAtDesc(
                userId,
                listOf(OrderStatus.PENDING, OrderStatus.PARTIALLY_FILLED),
            ).map { it.toResponse() }

    /** 수동 주문 트리거 — Kafka order.intent.v1 publish. */
    fun triggerOrder(
        userId: String,
        request: OrderRequest,
    ): OrderTriggerResponse {
        val intentId = UUID.randomUUID()
        publisher.publish(
            OrderIntentEvent(
                intentId = intentId,
                strategyId = "manual",
                signalId = UUID.randomUUID(),
                code = request.code,
                side = request.side,
                qty = request.qty,
                price = request.price,
                orderType = request.orderType,
                mode = "USER", // OrderDispatcherListener에서 cred.tradingMode 기반 분기로 덮어씀
                createdAt = Instant.now(),
            ),
        )
        return OrderTriggerResponse(intentId = intentId, publishedAt = OffsetDateTime.now())
    }
}

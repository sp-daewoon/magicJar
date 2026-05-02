package com.mj.consumer.messaging.listener

import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.event.OrderIntentEvent
import com.mj.domain.order.port.OrderSender
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * order.intent.v1 → OrderSender(profile별 분기) → order.executed.v1.
 *
 * 1번 트랙(T14) — 기존 BrokerGateway 의존을 OrderSender로 전환.
 * - mock profile: MockOrderSender → 즉시 FILLED publish
 * - dev/prd profile: KisOrderSender → Guard chain + KisOrderPort + 영속 + PENDING publish
 *   체결은 별도 흐름(KisExecutionNoticeListener·OrderExecutionSweep, T15)에서 추가 publish
 *
 * BrokerGateway 폐기 완료 (2026-05-03 v2 베이스라인) — KisBrokerGateway·FakeBrokerGateway 빈 모두 git rm.
 */
@Component
class OrderDispatcherListener(
    private val orderSender: OrderSender,
    private val orderExecutedPublisher: OrderExecutedPublisher,
) {
    private val log = LoggerFactory.getLogger(OrderDispatcherListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.orderIntent:order.intent.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:magicjar-consumer}",
    )
    fun onOrderIntent(event: OrderIntentEvent) {
        log.info(
            "OrderDispatcherListener received intent (intentId={}, code={}, side={})",
            event.intentId,
            event.code,
            event.side,
        )
        runCatching {
            val order =
                Order(
                    id = event.intentId, // intentId 그대로 orderId 사용 — orders.intent_id 매핑 일관
                    code = event.code,
                    side = event.side,
                    qty = event.qty,
                    price = event.price,
                    type = event.orderType,
                    status = OrderStatus.PENDING,
                    createdAt = Instant.now(),
                )
            val result = orderSender.send(order)
            val executed =
                OrderExecutedEvent(
                    orderId = result.order.id,
                    intentId = event.intentId,
                    code = event.code,
                    side = event.side,
                    executedQty = result.filledQty,
                    executedPrice = result.avgFillPrice ?: event.price ?: BigDecimal.ZERO,
                    status = result.resultStatus,
                    executedAt = result.filledAt ?: Instant.now(),
                    mode = event.mode,
                )
            orderExecutedPublisher.publish(executed)
            log.info(
                "OrderDispatcherListener published executed (intentId={}, status={})",
                event.intentId,
                result.resultStatus,
            )
        }.onFailure { ex ->
            log.error("OrderDispatcherListener failed (intentId={}): {}", event.intentId, ex.message, ex)
        }
    }
}

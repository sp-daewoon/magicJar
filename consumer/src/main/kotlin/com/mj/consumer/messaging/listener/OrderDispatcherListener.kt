package com.mj.consumer.messaging.listener

import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.event.OrderIntentEvent
import com.mj.domain.order.port.BrokerGateway
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.UUID

/**
 * order.intent.v1 → BrokerGateway(KIS 주문) → OrderExecutedPublisher.
 *
 * BrokerGateway는 @ConditionalOnMissingBean 으로 KIS 실구현 또는 FakeBrokerGateway 주입.
 */
@Component
class OrderDispatcherListener(
    private val brokerGateway: BrokerGateway,
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
                com.mj.domain.common.Order(
                    id = UUID.randomUUID(),
                    code = event.code,
                    side = event.side,
                    qty = event.qty,
                    price = event.price,
                    type = event.orderType,
                    status = com.mj.domain.common.OrderStatus.PENDING,
                    createdAt = Instant.now(),
                )
            val result = brokerGateway.placeOrder(order)
            val executed =
                OrderExecutedEvent(
                    orderId = result.order.id,
                    intentId = event.intentId,
                    code = event.code,
                    side = event.side,
                    executedQty = result.filledQty,
                    executedPrice = result.avgFillPrice ?: event.price ?: java.math.BigDecimal.ZERO,
                    status = result.resultStatus,
                    executedAt = result.filledAt ?: Instant.now(),
                    mode = event.mode,
                )
            orderExecutedPublisher.publish(executed)
        }.onFailure { ex ->
            log.error("OrderDispatcherListener failed (intentId={}): {}", event.intentId, ex.message)
        }
    }
}

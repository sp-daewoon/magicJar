package com.mj.api.messaging.listener

import com.mj.domain.order.event.OrderExecutedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * order.executed.v1 → STOMP /topic/orders fan-out.
 */
@Component
class OrderExecutedStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(OrderExecutedStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.orderExecuted:order.executed.v1}"],
        groupId = "magicjar-api-stomp",
    )
    fun onOrderExecuted(event: OrderExecutedEvent) {
        stomp.convertAndSend("/topic/orders", event)
        log.debug("OrderExecutedStompFanout → /topic/orders (orderId={})", event.orderId)
    }
}

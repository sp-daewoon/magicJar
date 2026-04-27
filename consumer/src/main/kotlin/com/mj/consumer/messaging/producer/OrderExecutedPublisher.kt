package com.mj.consumer.messaging.producer

import com.mj.domain.order.event.OrderExecutedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** order.executed.v1 토픽으로 KIS 체결 결과를 발행한다. */
@Component
class OrderExecutedPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.orderExecuted:order.executed.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(OrderExecutedPublisher::class.java)

    fun publish(event: OrderExecutedEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("OrderExecutedPublisher send failed (orderId={}): {}", event.orderId, ex.message)
                } else {
                    log.debug("OrderExecutedPublisher sent (orderId={})", event.orderId)
                }
            }
    }
}

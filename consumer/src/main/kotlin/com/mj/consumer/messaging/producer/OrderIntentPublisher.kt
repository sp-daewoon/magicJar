package com.mj.consumer.messaging.producer

import com.mj.domain.order.event.OrderIntentEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** order.intent.v1 토픽으로 주문 의도를 발행한다. */
@Component
class OrderIntentPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.orderIntent:order.intent.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(OrderIntentPublisher::class.java)

    fun publish(event: OrderIntentEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("OrderIntentPublisher send failed (code={}): {}", event.code, ex.message)
                } else {
                    log.debug("OrderIntentPublisher sent (intentId={})", event.intentId)
                }
            }
    }
}

package com.mj.api.messaging.producer

import com.mj.domain.order.event.OrderIntentEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * api 모듈에서 order.intent.v1 토픽으로 사용자 수동 주문을 발행한다.
 * consumer 모듈의 OrderIntentPublisher와 동일 토픽이지만 모듈 경계상 별도 빈.
 */
@Component
class OrderIntentApiPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.orderIntent:order.intent.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: OrderIntentEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[ORDER_INTENT] publish failed (code={}): {}", event.code, ex.message)
                } else {
                    log.info("[ORDER_INTENT] published (intentId={})", event.intentId)
                }
            }
    }
}

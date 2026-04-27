package com.mj.consumer.messaging.producer

import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** market.tick.v1 토픽으로 실시간 체결 데이터를 발행한다. */
@Component
class TickPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.tick:market.tick.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(TickPublisher::class.java)

    fun publish(event: TickEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("TickPublisher send failed (code={}): {}", event.code, ex.message)
                } else {
                    log.debug("TickPublisher sent (code={}, topic={})", event.code, topic)
                }
            }
    }
}

package com.mj.consumer.messaging.producer

import com.mj.domain.strategy.event.SignalEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** signal.v1 토픽으로 전략 신호를 발행한다. */
@Component
class SignalPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.signal:signal.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(SignalPublisher::class.java)

    fun publish(event: SignalEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("SignalPublisher send failed (code={}): {}", event.code, ex.message)
                } else {
                    log.debug("SignalPublisher sent (code={}, strategy={})", event.code, event.strategyId)
                }
            }
    }
}

package com.mj.consumer.messaging.producer

import com.mj.domain.market.event.IndexTickEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `market.index.v1` 토픽으로 KOSPI·KOSDAQ 지수 실시간 체결 데이터를 발행한다.
 *
 * Refs: plan T2-R2-A2 §T12.
 */
@Component
class IndexPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.marketIndex:market.index.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(IndexPublisher::class.java)

    fun publish(event: IndexTickEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("IndexPublisher send failed (code={}): {}", event.code, ex.message)
                } else {
                    log.debug("IndexPublisher sent (code={}, value={}, topic={})", event.code, event.value, topic)
                }
            }
    }
}

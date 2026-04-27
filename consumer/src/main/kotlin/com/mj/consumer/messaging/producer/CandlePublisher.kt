package com.mj.consumer.messaging.producer

import com.mj.domain.market.event.CandleEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/** market.candle.1m.v1 토픽으로 집계된 1분봉 캔들을 발행한다. */
@Component
class CandlePublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.candle1m:market.candle.1m.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(CandlePublisher::class.java)

    fun publish(event: CandleEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("CandlePublisher send failed (code={}): {}", event.code, ex.message)
                } else {
                    log.debug("CandlePublisher sent (code={}, interval={})", event.code, event.interval)
                }
            }
    }
}

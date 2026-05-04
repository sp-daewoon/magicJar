package com.mj.consumer.messaging.producer

import com.mj.domain.market.event.OrderbookEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `market.orderbook.v1` 토픽으로 KIS WS H0UNASP0 호가창 데이터를 발행한다.
 *
 * Refs: plan T2-R2-A2 §T10.
 */
@Component
class OrderbookPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.marketOrderbook:market.orderbook.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(OrderbookPublisher::class.java)

    fun publish(event: OrderbookEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("OrderbookPublisher send failed (code={}): {}", event.code, ex.message)
                } else {
                    log.debug(
                        "OrderbookPublisher sent (code={} ask1={} bid1={} topic={})",
                        event.code,
                        event.asks.firstOrNull()?.price,
                        event.bids.firstOrNull()?.price,
                        topic,
                    )
                }
            }
    }
}

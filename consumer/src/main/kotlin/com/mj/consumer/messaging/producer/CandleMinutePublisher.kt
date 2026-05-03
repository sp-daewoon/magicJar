package com.mj.consumer.messaging.producer

import com.mj.domain.market.event.CandleMinuteAggregatedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * candle.minute.aggregated.v1 토픽으로 1분봉 집계 이벤트를 발행한다.
 *
 * api 측 CandleMinuteEventListener가 수신 후 candle 테이블에 INSERT (interval=M1).
 */
@Component
class CandleMinutePublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.candleMinuteAggregated:candle.minute.aggregated.v1}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(CandleMinutePublisher::class.java)

    fun publish(event: CandleMinuteAggregatedEvent) {
        kafka
            .send(topic, event.code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn(
                        "CandleMinutePublisher send failed (code={}, openTime={}): {}",
                        event.code,
                        event.openTime,
                        ex.message,
                    )
                } else {
                    log.debug(
                        "CandleMinutePublisher sent (code={}, openTime={})",
                        event.code,
                        event.openTime,
                    )
                }
            }
    }
}

package com.mj.consumer.messaging.producer

import com.mj.domain.market.event.MarketCandleBackfillCommandEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `market.candle.backfill.command.v1` 토픽으로 일봉 backfill 명령을 발행한다 (consumer 측).
 *
 * 발행 주체:
 *   - DailyCandleBackfillScheduler (16:30 KST, SubscriptionManager.activeCodes() 기반)
 *
 * api 측 publisher와 같은 토픽 — batch listener가 dedup/멱등 처리.
 *
 * Refs: ADR 0049 §2-C.
 */
@Component
@Profile("dev | prd")
class MarketCandleBackfillCommandPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.marketCandleBackfillCommand:market.candle.backfill.command.v1}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun ensureBackfill(code: String) {
        val event = MarketCandleBackfillCommandEvent(code = code)
        kafka
            .send(topic, code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[BACKFILL_CMD] (consumer) send failed code={}: {}", code, ex.message)
                } else {
                    log.debug("[BACKFILL_CMD] (consumer) sent code={} topic={}", code, topic)
                }
            }
    }
}

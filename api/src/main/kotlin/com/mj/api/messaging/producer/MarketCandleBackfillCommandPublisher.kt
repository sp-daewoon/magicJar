package com.mj.api.messaging.producer

import com.mj.domain.market.event.MarketCandleBackfillCommandEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `market.candle.backfill.command.v1` 토픽으로 일봉 캔들 backfill 명령을 발행한다.
 *
 * 발행 시점:
 *   1) 사용자 종목 상세 진입 (QuoteController) — 차트 즉시 채움 보장.
 *   2) Scheduled 16:30 KST (DailyCandleBackfillScheduler) — default5 정기 갱신.
 *
 * batch listener가 멱등 처리 (DB max(openTime) ≥ 어제이면 skip).
 *
 * Refs: handoff 2026-05-04 D — 사용자 보고 "4-30 마지막 캔들 + default5 외 candle 0건".
 */
@Component
class MarketCandleBackfillCommandPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.marketCandleBackfillCommand:market.candle.backfill.command.v1}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 일봉 backfill 보장 명령 — best-effort 비동기 발행.
     */
    fun ensureBackfill(code: String) {
        val event = MarketCandleBackfillCommandEvent(code = code)
        kafka
            .send(topic, code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[BACKFILL_CMD] send failed code={}: {}", code, ex.message)
                } else {
                    log.debug("[BACKFILL_CMD] sent code={} topic={}", code, topic)
                }
            }
    }
}

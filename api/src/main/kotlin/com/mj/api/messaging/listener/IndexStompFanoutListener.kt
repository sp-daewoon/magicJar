package com.mj.api.messaging.listener

import com.mj.domain.market.event.IndexTickEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * `market.index.v1` → STOMP `/topic/market.index.{code}` fan-out.
 *
 * frontend Dashboard가 KOSPI·KOSDAQ 카드를 1초 간격 KIS WS tick으로 갱신할 수 있게 한다.
 *
 * Refs: plan T2-R2-A2 §T15.
 */
@Component
class IndexStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(IndexStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.marketIndex:market.index.v1}"],
        groupId = "magicjar-api-index-stomp",
    )
    fun onIndexTick(event: IndexTickEvent) {
        val destination = "/topic/market.index.${event.code}"
        stomp.convertAndSend(destination, event)
        log.debug(
            "IndexStompFanout → {} (value={} changeRate={}%)",
            destination,
            event.value,
            event.changeRate,
        )
    }
}

package com.mj.api.messaging.listener

import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * market.tick.v1 → STOMP /topic/market.tick.{symbol} fan-out.
 *
 * 프론트엔드 웹소켓 클라이언트에게 실시간 체결 데이터를 푸시한다.
 */
@Component
class TickStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(TickStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.tick:market.tick.v1}"],
        groupId = "magicjar-api-stomp",
    )
    fun onTick(event: TickEvent) {
        val destination = "/topic/market.tick.${event.code}"
        stomp.convertAndSend(destination, event)
        log.debug("TickStompFanout → {} (price={})", destination, event.price)
    }
}

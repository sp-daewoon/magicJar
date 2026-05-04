package com.mj.api.messaging.listener

import com.mj.domain.market.event.UserCandleUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * market.candle.user.v1 → STOMP /topic/market.candle.user.{userId}.{code} fanout.
 * ADR 0050 R3-5a — frontend가 새 candle 수신 즉시 차트에 반영 (페이지 새로고침 불요).
 *
 * consumer.UserCandleListener와 동일 토픽을 다른 consumer-group으로 구독:
 *   - consumer: groupId="magicjar-consumer-candle"  → Redis ZADD (single writer)
 *   - api(본 리스너): groupId="magicjar-api-candle-stomp" → STOMP fanout
 *
 * 두 listener는 독립 — broker가 같은 메시지를 두 group에 각각 전달.
 */
@Component
class UserCandleStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.userCandleUpdated:market.candle.user.v1}"],
        groupId = "magicjar-api-candle-stomp",
    )
    fun onUserCandleUpdated(event: UserCandleUpdatedEvent) {
        try {
            val destination = "/topic/market.candle.user.${event.userId}.${event.code}"
            stomp.convertAndSend(destination, event)
            log.debug(
                "[CANDLE_STOMP] → {} interval={} n={}",
                destination,
                event.interval,
                event.candles.size,
            )
        } catch (ex: Exception) {
            log.warn(
                "[CANDLE_STOMP] fanout 실패 user={} code={} interval={} error={}",
                event.userId,
                event.code,
                event.interval,
                ex.message,
            )
        }
    }
}

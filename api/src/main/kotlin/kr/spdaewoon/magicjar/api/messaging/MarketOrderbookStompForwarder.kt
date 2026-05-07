package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Kafka market.orderbook.v1 → STOMP /topic/market.orderbook.{code} 포워더 (ADR 0048 §5-1).
 *
 * 200ms throttle (호가 메시지 빈도가 높아 frontend 부담 차단).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class MarketOrderbookStompForwarder(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketOrderbookStompForwarder::class.java)

    // 200ms throttle: code → lastEmitMs
    private val lastEmitMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val throttleMs = 200L

    @KafkaListener(
        topics = ["market.orderbook.v1"],
        groupId = "magicjar-api-stomp-orderbook",
    )
    fun onOrderbookMessage(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            // 200ms throttle
            val now = System.currentTimeMillis()
            val last = lastEmitMs.getOrDefault(code, 0L)
            if (now - last < throttleMs) {
                log.debug("orderbook throttle skip: code={}", code)
                return
            }
            lastEmitMs[code] = now

            messagingTemplate.convertAndSend("/topic/market.orderbook.$code", payload)
            log.debug("STOMP /topic/market.orderbook.{} 발행", code)
        } catch (e: Exception) {
            log.error("market.orderbook STOMP 포워드 실패: offset={} err={}", record.offset(), e.message)
        }
    }
}

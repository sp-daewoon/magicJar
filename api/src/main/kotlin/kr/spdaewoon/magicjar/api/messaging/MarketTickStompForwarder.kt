package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * Kafka market.tick.v1 → STOMP /topic/market.tick.{code} 포워더 (ADR 0029 §2-5).
 *
 * 1초 throttle (in-memory ConcurrentHashMap code → lastEmitMs) — 사용자 path 부담 차단.
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class MarketTickStompForwarder(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(MarketTickStompForwarder::class.java)

    // 1초 throttle: code → lastEmitMs
    private val lastEmitMs: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val throttleMs = 1_000L

    @KafkaListener(
        topics = ["market.tick.v1"],
        groupId = "magicjar-api-stomp-tick",
    )
    fun onTickMessage(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            // 1초 throttle
            val now = System.currentTimeMillis()
            val last = lastEmitMs.getOrDefault(code, 0L)
            if (now - last < throttleMs) {
                log.debug("tick throttle skip: code={}", code)
                return
            }
            lastEmitMs[code] = now

            messagingTemplate.convertAndSend("/topic/market.tick.$code", payload)
            log.debug("STOMP /topic/market.tick.{} 발행: price={}", code, payload["price"])
        } catch (e: Exception) {
            log.error("market.tick STOMP 포워드 실패: offset={} err={}", record.offset(), e.message)
        }
    }
}

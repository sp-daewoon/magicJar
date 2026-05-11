package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * Kafka boundary closing 5종 토픽 → STOMP 5종 토픽 fanout (ADR 0064 C-1 채택).
 *
 * 구독 토픽 (ADR 0064 §4-5 + KafkaTopicConfig 5종):
 *   market.candle.5m.closed.v1   → /topic/market.candle.5m.{code}
 *   market.candle.15m.closed.v1  → /topic/market.candle.15m.{code}
 *   market.candle.30m.closed.v1  → /topic/market.candle.30m.{code}
 *   market.candle.90m.closed.v1  → /topic/market.candle.90m.{code}
 *   market.candle.1h.closed.v1   → /topic/market.candle.1h.{code}
 *
 * payload schema (BoundaryAggregator publish 정합 — ADR 0064 §4-5):
 *   { code, interval, ts, open, high, low, close, volume, source, publishedAt }
 *
 * STOMP topic 패턴 (ADR 0029 §2-5 LOCK):
 *   /topic/market.candle.{interval}.{code}
 *   예: /topic/market.candle.5m.005930
 *
 * frontend subscription 정합 (ADR 0064 §7-1 frontend 디스패치):
 *   useSymbolCandles.ts IntervalToggle 선택값 기반 dynamic subscribe/unsubscribe.
 *
 * throttle 미적용 — boundary 마감은 이미 저빈도 (5m=144건/일, 1h=12건/일 per code).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class CandleClosedStompListener(
    private val messagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(CandleClosedStompListener::class.java)

    /**
     * 5m boundary closing → STOMP /topic/market.candle.5m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-5m
     */
    @KafkaListener(
        topics = ["market.candle.5m.closed.v1"],
        groupId = "magicjar-api-stomp-boundary-5m",
    )
    fun on5mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "5m")
    }

    /**
     * 15m boundary closing → STOMP /topic/market.candle.15m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-15m
     */
    @KafkaListener(
        topics = ["market.candle.15m.closed.v1"],
        groupId = "magicjar-api-stomp-boundary-15m",
    )
    fun on15mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "15m")
    }

    /**
     * 30m boundary closing → STOMP /topic/market.candle.30m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-30m
     */
    @KafkaListener(
        topics = ["market.candle.30m.closed.v1"],
        groupId = "magicjar-api-stomp-boundary-30m",
    )
    fun on30mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "30m")
    }

    /**
     * 90m boundary closing → STOMP /topic/market.candle.90m.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-90m
     */
    @KafkaListener(
        topics = ["market.candle.90m.closed.v1"],
        groupId = "magicjar-api-stomp-boundary-90m",
    )
    fun on90mClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "90m")
    }

    /**
     * 1h boundary closing → STOMP /topic/market.candle.1h.{code} fanout.
     * groupId: magicjar-api-stomp-boundary-1h
     */
    @KafkaListener(
        topics = ["market.candle.1h.closed.v1"],
        groupId = "magicjar-api-stomp-boundary-1h",
    )
    fun on1hClosed(record: ConsumerRecord<String, String>) {
        forwardToStomp(record, "1h")
    }

    /**
     * Kafka payload → STOMP /topic/market.candle.{interval}.{code} 전달.
     *
     * STOMP topic: /topic/market.candle.{interval}.{code}
     * 예: /topic/market.candle.5m.005930
     *
     * @param record Kafka ConsumerRecord
     * @param interval 캔들 인터벌 코드 (예: "5m")
     */
    private fun forwardToStomp(
        record: ConsumerRecord<String, String>,
        interval: String,
    ) {
        try {
            @Suppress("UNCHECKED_CAST")
            val payload = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val code = payload["code"]?.toString() ?: return

            val topic = "/topic/market.candle.$interval.$code"
            messagingTemplate.convertAndSend(topic, payload)
            log.debug(
                "STOMP {} 발행: code={} ts={} close={}",
                topic,
                code,
                payload["ts"],
                payload["close"],
            )
        } catch (e: Exception) {
            log.error(
                "candle boundary STOMP 포워드 실패: interval={} offset={} err={}",
                interval,
                record.offset(),
                e.message,
            )
        }
    }
}

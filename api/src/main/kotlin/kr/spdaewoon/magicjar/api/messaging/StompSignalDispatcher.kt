package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.SignalResponse
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * `signal.generated.v1` Kafka 토픽 → STOMP `/topic/signal.user.{userId}` fanout.
 *
 * Phase 1 consumer가 발행한 시그널을 Kafka에서 수신하여
 * 해당 사용자의 STOMP 구독 토픽으로 실시간 전달 (ADR 0066 §2-3 D-1).
 *
 * ### groupId
 * `magicjar-api-stomp-signal` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 `magicjar-signal-generator` 그룹과 분리.
 *
 * ### STOMP 토픽
 * `/topic/signal.user.{userId}` — 사용자별 fanout (userId는 payload userId 필드).
 *
 * ### 에러 처리
 * 역직렬화 실패 시 log.error + skip (메시지 손실 허용 — 시그널은 DB 재조회 가능).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Component
class StompSignalDispatcher(
    private val brokerMessagingTemplate: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(StompSignalDispatcher::class.java)

    @KafkaListener(
        topics = ["signal.generated.v1"],
        groupId = "magicjar-api-stomp-signal",
    )
    fun onSignalGenerated(record: ConsumerRecord<String, String>) {
        try {
            @Suppress("UNCHECKED_CAST")
            val rawMap = objectMapper.readValue(record.value(), Map::class.java) as Map<String, Any?>
            val userId =
                rawMap["userId"]?.toString() ?: run {
                    log.warn("signal.generated.v1 userId null — offset={}", record.offset())
                    return
                }

            val payload = toSignalResponse(rawMap)
            val topic = "/topic/signal.user.$userId"
            brokerMessagingTemplate.convertAndSend(topic, payload)
            log.debug(
                "STOMP {} 발행: strategyId={} code={} side={}",
                topic,
                rawMap["strategyId"],
                rawMap["code"],
                rawMap["side"],
            )
        } catch (e: Exception) {
            log.error(
                "signal.generated.v1 STOMP 포워드 실패: offset={} err={}",
                record.offset(),
                e.message,
            )
        }
    }

    // --- private helper ---

    private fun toSignalResponse(raw: Map<String, Any?>): SignalResponse =
        SignalResponse(
            id = toLong(raw["id"]),
            strategyId = toLong(raw["strategyId"]),
            userId = raw["userId"]?.toString() ?: "",
            code = raw["code"]?.toString() ?: "",
            interval = raw["interval"]?.toString() ?: "",
            ts = raw["ts"]?.toString() ?: "",
            side = raw["side"]?.toString() ?: "HOLD",
            strength = toInt(raw["strength"]),
            reason = raw["reason"]?.toString() ?: "",
            priceAtSignal = toBigDecimal(raw["priceAtSignal"]),
            contextJson = raw["contextJson"]?.toString(),
            createdAt = raw["createdAt"]?.toString(),
        )

    private fun toLong(value: Any?): Long =
        when (value) {
            is Number -> value.toLong()
            is String -> value.toLongOrNull() ?: 0L
            else -> 0L
        }

    private fun toInt(value: Any?): Int =
        when (value) {
            is Number -> value.toInt()
            is String -> value.toIntOrNull() ?: 0
            else -> 0
        }

    private fun toBigDecimal(value: Any?): BigDecimal =
        when (value) {
            is Number -> BigDecimal(value.toString())
            is String -> runCatching { BigDecimal(value) }.getOrDefault(BigDecimal.ZERO)
            else -> BigDecimal.ZERO
        }
}

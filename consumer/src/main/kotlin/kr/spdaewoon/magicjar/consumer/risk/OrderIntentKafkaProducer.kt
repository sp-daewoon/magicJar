package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.signal.Signal
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 주문 의도(order.intent.v1) Kafka publish 어댑터 (ADR 0067 §7-1 + E-1 정합).
 *
 * partition key = `userId` — 사용자별 ordering 보장.
 *
 * payload schema (Phase 1 자체 정의 — T-O ADR 진입 시 확정):
 * { signalId, strategyId, userId, code, interval, ts(epochSeconds), side, strength,
 *   priceAtSignal, riskVerdict: "ALLOWED", emittedAt(epochSeconds) }
 *
 * spec 인용: ADR 0067 §7-1 OrderIntentKafkaProducer.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class OrderIntentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderIntentKafkaProducer::class.java)

    companion object {
        const val TOPIC = "order.intent.v1"
    }

    fun publish(signal: Signal) {
        try {
            val now =
                java.time.Instant
                    .now()
                    .epochSecond
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "signalId" to signal.id,
                        "strategyId" to signal.strategyId,
                        "userId" to signal.userId,
                        "code" to signal.code,
                        "interval" to signal.interval.code,
                        "ts" to signal.ts.epochSeconds,
                        "side" to signal.side.name,
                        "strength" to signal.strength,
                        "priceAtSignal" to signal.priceAtSignal.toPlainString(),
                        "riskVerdict" to "ALLOWED",
                        "emittedAt" to now,
                    ),
                )
            kafkaTemplate.send(TOPIC, signal.userId, payload)
            log.debug(
                "OrderIntentKafkaProducer publish: topic={} userId={} signalId={} code={} side={}",
                TOPIC,
                signal.userId,
                signal.id,
                signal.code,
                signal.side,
            )
        } catch (e: Exception) {
            log.error(
                "OrderIntentKafkaProducer publish 실패: userId={} signalId={} err={}",
                signal.userId,
                signal.id,
                e.message,
            )
        }
    }
}

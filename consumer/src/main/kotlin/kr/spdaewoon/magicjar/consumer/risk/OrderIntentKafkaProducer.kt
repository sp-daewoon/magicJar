package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.persistence.shared.metric.PersistenceMetrics
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
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
 * ### Transactional Outbox 패턴 (ADR 0084 P1-1 + spec §2-5 LOCK)
 *
 * 1. payload 직렬화 → outbox row INSERT (PENDING)
 * 2. Kafka send → outbox markPublished
 * 3. Kafka send 실패 시 outbox row 잔존 → OrderOutboxPublisherWorker가 비동기 retry
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0084 §1-2)
 */
@Component
class OrderIntentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
    // ADR 0094 P0-OPS-6 + ADR 0095 §2-1 S-1 — signal emit ~ order intent publish latency Timer
    private val persistenceMetricsProvider: ObjectProvider<PersistenceMetrics>,
) {
    private val log = LoggerFactory.getLogger(OrderIntentKafkaProducer::class.java)

    companion object {
        const val TOPIC = "order.intent.v1"
    }

    fun publish(signal: Signal) {
        val now =
            java.time.Instant
                .now()
                .epochSecond
        val payload =
            try {
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
            } catch (e: Exception) {
                log.error(
                    "OrderIntentKafkaProducer payload 직렬화 실패: userId={} signalId={} err={}",
                    signal.userId,
                    signal.id,
                    e.message,
                )
                return
            }

        val outboxRepository = outboxRepositoryProvider.getIfAvailable()
        val outboxId =
            outboxRepository?.let {
                runCatching {
                    it
                        .save(
                            OrderEventOutbox(
                                eventType = OrderOutboxEventType.ORDER_INTENT,
                                topic = TOPIC,
                                partitionKey = signal.userId,
                                payloadJson = payload,
                            ),
                        ).id
                }.onFailure { e ->
                    log.error(
                        "OrderIntentKafkaProducer outbox INSERT 실패 — direct publish fallback: userId={} signalId={} err={}",
                        signal.userId,
                        signal.id,
                        e.message,
                    )
                }.getOrNull()
            }

        try {
            kafkaTemplate.send(TOPIC, signal.userId, payload)
            if (outboxId != null) {
                runCatching { outboxRepository?.markPublished(outboxId) }
                    .onFailure { e ->
                        log.warn(
                            "OrderIntentKafkaProducer outbox markPublished 실패 (publish는 성공): outboxId={} err={}",
                            outboxId,
                            e.message,
                        )
                    }
            }
            // ADR 0095 S-1 측정 — signal.ts(epoch s) → now(epoch s) latency Timer record
            persistenceMetricsProvider
                .getIfAvailable()
                ?.recordSignalToOrderLatency(signal.ts.epochSeconds, now)
            log.debug(
                "OrderIntentKafkaProducer publish: topic={} userId={} signalId={} code={} side={}",
                TOPIC,
                signal.userId,
                signal.id,
                signal.code,
                signal.side,
            )
        } catch (e: Exception) {
            if (outboxId != null) {
                runCatching {
                    outboxRepository?.recordAttemptFailure(outboxId, "publish: ${e.message?.take(200) ?: "unknown"}")
                }
            }
            log.error(
                "OrderIntentKafkaProducer publish 실패 (outbox retry 예약): userId={} signalId={} outboxId={} err={}",
                signal.userId,
                signal.id,
                outboxId,
                e.message,
            )
        }
    }
}

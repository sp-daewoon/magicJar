package kr.spdaewoon.magicjar.api.messaging.journal

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.journal.JournalEventType
import kr.spdaewoon.magicjar.domain.journal.TradingJournalEntry
import kr.spdaewoon.magicjar.domain.journal.port.TradingJournalRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * 매매일지 Kafka 이벤트 소비자 — ADR 0072 §4·§9-2 Phase 1 (G-1 5 토픽 listen LOCK).
 *
 * 5 토픽 listen:
 * - order.filled.v1 → ORDER_FILLED entry INSERT
 * - order.rejected.v1 → ORDER_REJECTED entry INSERT
 * - order.cancelled.v1 → ORDER_CANCELLED entry INSERT
 * - signal.generated.v1 → SIGNAL_GENERATED entry INSERT
 * - risk.violation.v1 → RISK_VIOLATION entry INSERT
 *
 * 멱등성: DB UQ `ux_trading_journal_source` (source_table, source_id, event_type) 위반 시
 * [TradingJournalRepositoryAdapter]가 null 반환 → log.warn + skip. Kafka at-least-once 안전.
 *
 * Kafka 신규 publish 0 LOCK (기존 5 토픽 listen만 — ADR 0072 §4).
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
@Component
class JournalEventConsumer(
    private val journalRepository: TradingJournalRepository,
    private val summaryComposer: JournalSummaryComposer,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(JournalEventConsumer::class.java)

    companion object {
        const val GROUP = "journal-event-consumer"
    }

    /**
     * order.filled.v1 listen — 체결 이벤트 → trading_journal INSERT.
     *
     * payload 필드: orderId·userId·code·side·executedQuantity·executedPrice·executedAt·
     *               signalId·strategyId·kisOrderNo·partial·mode·instrumentName(nullable)
     */
    @KafkaListener(topics = ["order.filled.v1"], groupId = GROUP)
    fun onOrderFilled(record: ConsumerRecord<String, String>) {
        processEvent(record, "order.filled.v1") { payload ->
            val userId = parseUserId(payload) ?: return@processEvent null
            val orderId = (payload["orderId"] as? Number)?.toLong() ?: return@processEvent null
            val code = payload["code"]?.toString() ?: return@processEvent null
            val side = payload["side"]?.toString() ?: "BUY"
            val qty = payloadBigDecimal(payload, "executedQuantity") ?: BigDecimal.ONE
            val price = payloadBigDecimal(payload, "executedPrice") ?: BigDecimal.ZERO
            val executedAt = parseInstant(payload, "executedAt") ?: Instant.now()
            val instrumentName = payload["instrumentName"]?.toString()

            val enrichedPayload = payload + mapOf("instrumentName" to instrumentName)
            val summary = summaryComposer.compose(JournalEventType.ORDER_FILLED, enrichedPayload)

            TradingJournalEntry(
                userId = userId,
                eventType = JournalEventType.ORDER_FILLED,
                instrumentCode = code,
                instrumentName = instrumentName,
                occurredAt = executedAt,
                summary = summary,
                payload = objectMapper.writeValueAsString(enrichedPayload),
                sourceTable = "order_execution",
                sourceId = orderId,
                realizedPnl = payloadBigDecimal(payload, "realizedPnl"),
            )
        }
    }

    /**
     * order.rejected.v1 listen — 주문 거부 이벤트 → trading_journal INSERT.
     *
     * payload 필드: orderId·userId·code·side·quantity·rejectReason·rejectedAt·instrumentName(nullable)
     */
    @KafkaListener(topics = ["order.rejected.v1"], groupId = GROUP)
    fun onOrderRejected(record: ConsumerRecord<String, String>) {
        processEvent(record, "order.rejected.v1") { payload ->
            val userId = parseUserId(payload) ?: return@processEvent null
            val orderId = (payload["orderId"] as? Number)?.toLong() ?: return@processEvent null
            val code = payload["code"]?.toString() ?: return@processEvent null
            val side = payload["side"]?.toString() ?: "BUY"
            val qty = payloadBigDecimal(payload, "quantity")
            val reason = payload["rejectReason"]?.toString()
            val rejectedAt = parseInstant(payload, "rejectedAt") ?: Instant.now()
            val instrumentName = payload["instrumentName"]?.toString()

            val enrichedPayload = payload + mapOf("instrumentName" to instrumentName)
            val summary = summaryComposer.composeOrderRejected(instrumentName, code, side, qty, reason)

            TradingJournalEntry(
                userId = userId,
                eventType = JournalEventType.ORDER_REJECTED,
                instrumentCode = code,
                instrumentName = instrumentName,
                occurredAt = rejectedAt,
                summary = summary,
                payload = objectMapper.writeValueAsString(enrichedPayload),
                sourceTable = "order_request",
                sourceId = orderId,
            )
        }
    }

    /**
     * order.cancelled.v1 listen — 주문 취소 이벤트 → trading_journal INSERT.
     *
     * payload 필드: orderId·userId·code·side·quantity·cancelledAt·instrumentName(nullable)
     */
    @KafkaListener(topics = ["order.cancelled.v1"], groupId = GROUP)
    fun onOrderCancelled(record: ConsumerRecord<String, String>) {
        processEvent(record, "order.cancelled.v1") { payload ->
            val userId = parseUserId(payload) ?: return@processEvent null
            val orderId = (payload["orderId"] as? Number)?.toLong() ?: return@processEvent null
            val code = payload["code"]?.toString() ?: return@processEvent null
            val side = payload["side"]?.toString() ?: "BUY"
            val qty = payloadBigDecimal(payload, "quantity")
            val cancelledAt = parseInstant(payload, "cancelledAt") ?: Instant.now()
            val instrumentName = payload["instrumentName"]?.toString()

            val enrichedPayload = payload + mapOf("instrumentName" to instrumentName)
            val summary = summaryComposer.composeOrderCancelled(instrumentName, code, side, qty)

            TradingJournalEntry(
                userId = userId,
                eventType = JournalEventType.ORDER_CANCELLED,
                instrumentCode = code,
                instrumentName = instrumentName,
                occurredAt = cancelledAt,
                summary = summary,
                payload = objectMapper.writeValueAsString(enrichedPayload),
                sourceTable = "order_request",
                sourceId = orderId,
            )
        }
    }

    /**
     * signal.generated.v1 listen — 시그널 발생 이벤트 → trading_journal INSERT.
     *
     * payload 필드: signalId·userId·code·side·strength·ts·strategyId·instrumentName(nullable)
     */
    @KafkaListener(topics = ["signal.generated.v1"], groupId = GROUP)
    fun onSignalGenerated(record: ConsumerRecord<String, String>) {
        processEvent(record, "signal.generated.v1") { payload ->
            val userId = parseUserId(payload) ?: return@processEvent null
            val signalId = (payload["signalId"] as? Number)?.toLong() ?: return@processEvent null
            val code = payload["code"]?.toString() ?: return@processEvent null
            val side = payload["side"]?.toString() ?: "BUY"
            val strength = (payload["strength"] as? Number)?.toInt() ?: 0
            val ts = parseInstant(payload, "ts") ?: Instant.now()
            val instrumentName = payload["instrumentName"]?.toString()

            val enrichedPayload = payload + mapOf("instrumentName" to instrumentName)
            val summary = summaryComposer.composeSignalGenerated(instrumentName, code, side, strength)

            TradingJournalEntry(
                userId = userId,
                eventType = JournalEventType.SIGNAL_GENERATED,
                instrumentCode = code,
                instrumentName = instrumentName,
                occurredAt = ts,
                summary = summary,
                payload = objectMapper.writeValueAsString(enrichedPayload),
                sourceTable = "signal",
                sourceId = signalId,
            )
        }
    }

    /**
     * risk.violation.v1 listen — 리스크 위반 이벤트 → trading_journal INSERT.
     *
     * payload 필드: riskEventId·userId·ruleType·code(nullable)·violatedAt
     */
    @KafkaListener(topics = ["risk.violation.v1"], groupId = GROUP)
    fun onRiskViolation(record: ConsumerRecord<String, String>) {
        processEvent(record, "risk.violation.v1") { payload ->
            val userId = parseUserId(payload) ?: return@processEvent null
            val riskEventId = (payload["riskEventId"] as? Number)?.toLong() ?: return@processEvent null
            val ruleType = payload["ruleType"]?.toString() ?: "UNKNOWN"
            val code = payload["code"]?.toString()
            val violatedAt = parseInstant(payload, "violatedAt") ?: Instant.now()

            val summary = summaryComposer.composeRiskViolation(ruleType, code)

            TradingJournalEntry(
                userId = userId,
                eventType = JournalEventType.RISK_VIOLATION,
                instrumentCode = code,
                occurredAt = violatedAt,
                summary = summary,
                payload = objectMapper.writeValueAsString(payload),
                sourceTable = "risk_event",
                sourceId = riskEventId,
            )
        }
    }

    // --- private helpers ---

    /**
     * 공통 이벤트 처리 템플릿.
     * [entryBuilder] null 반환 시 poison-pill로 간주 + log.warn + skip.
     */
    private fun processEvent(
        record: ConsumerRecord<String, String>,
        topic: String,
        entryBuilder: (Map<String, Any?>) -> TradingJournalEntry?,
    ) {
        try {
            val payload =
                deserializePayload(record.value()) ?: run {
                    log.warn("JournalEventConsumer deserialize 실패 (poison-pill skip): topic={} offset={}", topic, record.offset())
                    return
                }
            val entry =
                entryBuilder(payload) ?: run {
                    log.warn("JournalEventConsumer entry 빌드 실패 (필수 필드 누락): topic={} offset={}", topic, record.offset())
                    return
                }
            val saved = journalRepository.saveEntry(entry)
            if (saved == null) {
                log.warn(
                    "JournalEventConsumer UQ 충돌 skip (멱등성): topic={} sourceTable={} sourceId={} eventType={}",
                    topic,
                    entry.sourceTable,
                    entry.sourceId,
                    entry.eventType,
                )
            } else {
                log.info(
                    "JournalEventConsumer INSERT 완료: topic={} id={} userId={} eventType={}",
                    topic,
                    saved.id,
                    saved.userId,
                    saved.eventType,
                )
            }
        } catch (e: Exception) {
            log.error(
                "JournalEventConsumer 처리 실패: topic={} offset={} err={}",
                topic,
                record.offset(),
                e.message,
                e,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun deserializePayload(value: String?): Map<String, Any?>? {
        if (value.isNullOrBlank()) return null
        return try {
            objectMapper.readValue(value, Map::class.java) as Map<String, Any?>
        } catch (e: Exception) {
            log.error("JournalEventConsumer JSON 파싱 실패: err={}", e.message)
            null
        }
    }

    private fun parseUserId(payload: Map<String, Any?>): Long? {
        val raw = payload["userId"] ?: return null
        return when (raw) {
            is Number -> raw.toLong()
            is String -> raw.toLongOrNull()
            else -> null
        }
    }

    private fun parseInstant(
        payload: Map<String, Any?>,
        key: String,
    ): Instant? {
        val raw = payload[key] ?: return null
        return when (raw) {
            is Number -> Instant.ofEpochSecond(raw.toLong())
            is String ->
                runCatching { Instant.parse(raw) }.getOrElse {
                    raw.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
                }
            else -> null
        }
    }

    private fun payloadBigDecimal(
        payload: Map<String, Any?>,
        key: String,
    ): BigDecimal? =
        when (val v = payload[key]) {
            is Number -> BigDecimal.valueOf(v.toDouble())
            is String -> v.toBigDecimalOrNull()
            else -> null
        }
}

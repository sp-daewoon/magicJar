package kr.spdaewoon.magicjar.persistence.shared.event

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

/**
 * SystemEvent → Kafka `system.event.v1` publish (P2 STOMP fanout 트리거 source).
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6.2
 *
 * ### partition key 정책
 * - userId 있으면 → userId (사용자별 ordering 보장 — UI 알림센터 시계열 정합)
 * - userId null  → "broadcast" (system-wide 이벤트 — STOMP `/topic/system.event.broadcast`)
 *
 * ### 실패 정책
 * - send `.get(5s)` blocking — 호출 측 [SystemEventLoggerAdapter] 가 `runCatching` 으로 wrap
 * - Kafka broker down 시 IO/TimeoutException — adapter 가 log.warn 후 DB 영속만 유지 (graceful)
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
@Component
class SystemEventKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SystemEventKafkaProducer::class.java)

    companion object {
        const val TOPIC = "system.event.v1"
        const val SEND_TIMEOUT_SECONDS = 5L
        const val BROADCAST_KEY = "broadcast"
    }

    fun publish(event: SystemEvent) {
        val partitionKey = event.userId ?: BROADCAST_KEY
        val payload = objectMapper.writeValueAsString(event)
        kafkaTemplate
            .send(TOPIC, partitionKey, payload)
            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        log.debug(
            "SystemEvent publish OK: category={} severity={} action={} partitionKey={}",
            event.category,
            event.severity,
            event.action,
            partitionKey,
        )
    }
}

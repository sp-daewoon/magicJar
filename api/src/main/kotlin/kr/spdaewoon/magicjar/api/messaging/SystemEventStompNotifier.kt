package kr.spdaewoon.magicjar.api.messaging

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * P2 — Kafka `system.event.v1` → STOMP fanout.
 *
 * ### 분기 (spec §11 LOCK)
 * - `event.userId != null` → `/topic/system.event.user.{userId}` (user-specific fanout)
 * - `event.userId == null` → `/topic/system.event.broadcast` (system-wide fanout)
 *
 * ### graceful
 * 잘못된 payload (역직렬화 실패) 또는 STOMP publish 실패는 listener thread 안 죽임 —
 * `runCatching` wrap + `log.error` 만 (offset/error 흔적 남기고 다음 메시지 진행).
 * 손실 허용 — DB 영속(SystemEventLoggerAdapter)이 source-of-truth, STOMP는 push 보조.
 *
 * ### 모듈 경계
 * api 모듈 단독 — consumer 모듈이 STOMP 직접 호출하지 않음 (ADR 0004 정합).
 * PR #621 ([StompAccountNotifier]) 패턴 1:1 정합.
 *
 * ### groupId
 * `magicjar-system-event-stomp` — api 모듈 STOMP fanout 전용 그룹.
 * consumer 모듈의 발행 path와 group 분리 (offset 독립).
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6.4
 * plan: `_workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md` Task 1
 */
@Component
class SystemEventStompNotifier(
    private val stomp: SimpMessagingTemplate,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SystemEventStompNotifier::class.java)

    companion object {
        const val TOPIC_USER_PREFIX = "/topic/system.event.user."
        const val TOPIC_BROADCAST = "/topic/system.event.broadcast"
        const val KAFKA_TOPIC = "system.event.v1"
        const val GROUP_ID = "magicjar-system-event-stomp"
    }

    @KafkaListener(topics = [KAFKA_TOPIC], groupId = GROUP_ID)
    fun onMessage(record: ConsumerRecord<String, String>) {
        runCatching {
            val event = objectMapper.readValue<SystemEvent>(record.value())
            val topic =
                if (event.userId != null) {
                    "$TOPIC_USER_PREFIX${event.userId}"
                } else {
                    TOPIC_BROADCAST
                }
            stomp.convertAndSend(topic, event)
            log.debug(
                "SystemEvent STOMP fanout: topic={} category={} severity={} action={}",
                topic,
                event.category,
                event.severity,
                event.action,
            )
        }.onFailure { e ->
            log.error("SystemEvent STOMP fanout 실패: offset={} err={}", record.offset(), e.message)
        }
    }
}

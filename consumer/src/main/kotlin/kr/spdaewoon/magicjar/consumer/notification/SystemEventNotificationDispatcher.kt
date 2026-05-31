package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationEventType
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.UUID

/**
 * SystemEvent → Slack notification bridge.
 *
 * INFO는 내부 audit/UI로만 남기고, ERROR/CRITICAL은 즉시 Slack(P0), WARN은 Redis window로 묶어 P2 outbox에 넣는다.
 */
@Component
class SystemEventNotificationDispatcher(
    private val outboxRepositoryProvider: ObjectProvider<NotificationOutboxRepository>,
    private val rateLimiterProvider: ObjectProvider<NotificationRateLimiterPort>,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(SystemEventNotificationDispatcher::class.java)

    companion object {
        private const val WARN_THRESHOLD = 3
        private const val WARN_WINDOW_MIN = 10
    }

    @KafkaListener(topics = ["system.event.v1"], groupId = "magicjar-notification-system-event")
    fun onSystemEvent(record: ConsumerRecord<String, String>) {
        try {
            val event = objectMapper.readValue<SystemEvent>(record.value())
            val priority = priorityOf(event.severity) ?: return
            val userId = event.userId ?: "broadcast"

            if (priority == NotificationPriority.P2 && !shouldEmitWarn(userId, event)) {
                log.debug(
                    "SystemEvent WARN rate limit skip — category={} action={} userId={}",
                    event.category,
                    event.action,
                    userId,
                )
                return
            }

            val reason = buildReason(event)
            val payloadJson =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to eventIdOf(event).toString(),
                        "eventType" to NotificationEventType.SYSTEM_EVENT.name,
                        "userId" to userId,
                        "reason" to reason,
                        "meta" to
                            mapOf(
                                "category" to event.category.name,
                                "severity" to event.severity.name,
                                "actor" to event.actor,
                                "action" to event.action,
                                "correlationId" to event.correlationId,
                            ),
                        "createdAt" to Instant.now().epochSecond,
                    ),
                )

            val outbox =
                NotificationOutbox(
                    eventId = eventIdOf(event),
                    channel = NotificationChannel.SLACK,
                    priority = priority,
                    payloadJson = payloadJson,
                    status = NotificationStatus.PENDING,
                    createdAt = Instant.now(),
                )

            val outboxRepository = outboxRepositoryProvider.getIfAvailable()
            if (outboxRepository == null) {
                log.warn("NotificationOutboxRepository 미등록 — SystemEvent Slack outbox skip: action={}", event.action)
                if (priority == NotificationPriority.P0) {
                    sendDirect(userId, reason)
                }
                return
            }

            val saved = outboxRepository.save(outbox)
            log.info(
                "SystemEvent Slack outbox INSERT: id={} severity={} action={} priority={}",
                saved.id,
                event.severity,
                event.action,
                priority,
            )

            if (priority == NotificationPriority.P0) {
                sendImmediate(saved, outboxRepository)
            }
        } catch (e: Exception) {
            log.error(
                "SystemEvent Slack bridge 처리 실패: topic={} offset={} err={}",
                record.topic(),
                record.offset(),
                e.message,
                e,
            )
        }
    }

    private fun priorityOf(severity: SystemEventSeverity): NotificationPriority? =
        when (severity) {
            SystemEventSeverity.CRITICAL,
            SystemEventSeverity.ERROR,
            -> NotificationPriority.P0
            SystemEventSeverity.WARN -> NotificationPriority.P2
            SystemEventSeverity.INFO -> null
        }

    private fun shouldEmitWarn(
        userId: String,
        event: SystemEvent,
    ): Boolean {
        val limiter = rateLimiterProvider.getIfAvailable() ?: return true
        val topicHash = "system.${event.category.name.lowercase()}.${event.action.lowercase()}"
        return limiter.shouldEmit(userId, topicHash, WARN_THRESHOLD, WARN_WINDOW_MIN)
    }

    private fun sendImmediate(
        outbox: NotificationOutbox,
        outboxRepository: NotificationOutboxRepository,
    ) {
        val sender = slackSenderProvider.getIfAvailable()
        val id = outbox.id ?: return
        if (sender == null) {
            log.warn("SlackWebhookSender 미등록 — SystemEvent P0 즉시 발송 skip, outbox PENDING 유지: id={}", id)
            return
        }

        try {
            val sent = sender.sendOutbox(outbox)
            if (sent) {
                outboxRepository.markSent(id)
                log.info("SystemEvent P0 Slack 발송 성공: outboxId={}", id)
            } else {
                outboxRepository.incrementRetryCount(id)
                log.warn("SystemEvent P0 Slack 발송 실패 — PENDING 유지: outboxId={}", id)
            }
        } catch (e: Exception) {
            log.error("SystemEvent P0 Slack 발송 예외: outboxId={} err={}", id, e.message)
            outboxRepository.incrementRetryCount(id)
        }
    }

    private fun sendDirect(
        userId: String,
        reason: String,
    ) {
        val sender = slackSenderProvider.getIfAvailable() ?: return
        val message = NotificationFormatter.format(userId, NotificationEventType.SYSTEM_EVENT, reason, emptyMap())
        sender.sendDirect(message)
    }

    private fun buildReason(event: SystemEvent): String =
        "[${event.severity}] ${event.category}/${event.action} actor=${event.actor} detail=${event.detailJson.take(500)}"

    private fun eventIdOf(event: SystemEvent): UUID {
        val seed =
            listOf(
                event.category.name,
                event.severity.name,
                event.actor,
                event.action,
                event.userId.orEmpty(),
                event.correlationId.orEmpty(),
                event.occurredAt.toString(),
            ).joinToString("|")
        return UUID.nameUUIDFromBytes(seed.toByteArray(StandardCharsets.UTF_8))
    }
}

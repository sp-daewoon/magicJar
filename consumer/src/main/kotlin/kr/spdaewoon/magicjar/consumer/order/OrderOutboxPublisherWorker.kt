package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.notification.NotificationChannel
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.reconcile.port.KillSwitchTogglePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * order_event_outbox PENDING 재시도 worker (ADR 0084 P1-1 + spec 2026-05-18 §2-5 LOCK).
 *
 * ### 책임
 * - 4 producer (Intent·Requested·Rejected·Filled)가 Kafka publish 실패 시 잔존시킨 outbox row 비동기 retry
 * - attempt_count ≥ MAX_ATTEMPT 시 FAILED 전환 + P0 운영 알림 + KillSwitch auto-ON (자본 path 보호 — ADR 0043 정합)
 *
 * ### 스케줄
 * `@Scheduled(fixedDelay = 5_000)` — 5초 주기 (spec §2-5 본문 권장).
 * fixedDelay 사용: 직전 실행 완료 후 5초 대기 (동시 실행 방지).
 *
 * ### 처리 흐름
 * 1. findPending(limit=100) — published_at IS NULL · 오래된 순
 * 2. 각 row → kafkaTemplate.send(topic, partitionKey, payloadJson)
 * 3. 성공: markPublished
 * 4. 실패: attempt_count++ + last_error 갱신
 *    attempt_count ≥ MAX_ATTEMPT(3) → markFailed + 운영 알림 + KillSwitch engage
 *
 * ### graceful 조건
 * - OrderEventOutboxRepository 미등록 → skip + log.warn
 * - KafkaTemplate 미등록 시 부팅 자체가 실패하므로 별도 가드 0 (Spring Kafka 의무)
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
@Component
class OrderOutboxPublisherWorker(
    private val outboxRepositoryProvider: ObjectProvider<OrderEventOutboxRepository>,
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
    private val notificationOutboxRepositoryProvider: ObjectProvider<NotificationOutboxRepository>,
    private val killSwitchTogglePortProvider: ObjectProvider<KillSwitchTogglePort>,
) {
    private val log = LoggerFactory.getLogger(OrderOutboxPublisherWorker::class.java)

    companion object {
        const val MAX_ATTEMPT = 3
        const val BATCH_SIZE = 100
        const val FIXED_DELAY_MS = 5_000L
        const val KILLSWITCH_REASON = "ORDER_OUTBOX_DRAIN_FAILED"
    }

    @Scheduled(fixedDelay = FIXED_DELAY_MS)
    fun drainPending() {
        val outboxRepository = outboxRepositoryProvider.getIfAvailable()
        if (outboxRepository == null) {
            log.warn("OrderEventOutboxRepository 미등록 — drain worker skip")
            return
        }

        val pending = outboxRepository.findPending(limit = BATCH_SIZE)
        if (pending.isEmpty()) {
            log.trace("drain worker: PENDING 0건 — skip")
            return
        }

        log.info("drain worker: PENDING {}건 재시도 시작", pending.size)
        var publishedCount = 0
        var failedCount = 0
        var retriedCount = 0

        for (outbox in pending) {
            val id = outbox.id ?: continue
            try {
                val key = outbox.partitionKey ?: outbox.eventId.toString()
                kafkaTemplate.send(outbox.topic, key, outbox.payloadJson).get()
                outboxRepository.markPublished(id)
                publishedCount++
                log.info(
                    "drain 성공: outboxId={} topic={} attemptCount={}",
                    id,
                    outbox.topic,
                    outbox.attemptCount,
                )
            } catch (e: Exception) {
                val newAttempt = outbox.attemptCount + 1
                val errMsg = e.message?.take(200) ?: "unknown"
                if (newAttempt >= MAX_ATTEMPT) {
                    outboxRepository.markFailed(id, "attempt=$newAttempt err=$errMsg")
                    failedCount++
                    log.error(
                        "drain 소진 FAILED: outboxId={} topic={} attempt={} err={} — P0 알림 + KillSwitch engage",
                        id,
                        outbox.topic,
                        newAttempt,
                        errMsg,
                    )
                    raiseFailureAlertSafely(outbox, errMsg)
                    engageKillSwitchSafely(outbox)
                } else {
                    outboxRepository.recordAttemptFailure(id, "attempt=$newAttempt err=$errMsg")
                    retriedCount++
                    log.warn(
                        "drain 실패 {}/{}: outboxId={} topic={} err={}",
                        newAttempt,
                        MAX_ATTEMPT,
                        id,
                        outbox.topic,
                        errMsg,
                    )
                }
            }
        }

        log.info(
            "drain worker 완료: pending={} published={} retried={} failed={}",
            pending.size,
            publishedCount,
            retriedCount,
            failedCount,
        )
    }

    // --- private helper ---

    /**
     * FAILED outbox 발생 시 P0 알림 발사 — NotificationOutbox INSERT (ADR 0044 + ADR 0070).
     *
     * 본 worker는 STOMP/Slack 직접 호출 0 — outbox 패턴 정합 (NotificationOutboxRetryWorker가 발송 처리).
     */
    private fun raiseFailureAlertSafely(
        outbox: OrderEventOutbox,
        errMsg: String,
    ) {
        val repo = notificationOutboxRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.warn("NotificationOutboxRepository 미등록 — drain failure 알림 skip outboxId={}", outbox.id)
            return
        }
        runCatching {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "alertType" to "ORDER_OUTBOX_DRAIN_FAILED",
                        "outboxId" to outbox.id,
                        "eventType" to outbox.eventType.name,
                        "topic" to outbox.topic,
                        "partitionKey" to outbox.partitionKey,
                        "attemptCount" to outbox.attemptCount + 1,
                        "lastError" to errMsg,
                        "createdAt" to outbox.createdAt.epochSecond,
                    ),
                )
            repo.save(
                NotificationOutbox(
                    eventId = UUID.randomUUID(),
                    channel = NotificationChannel.SLACK,
                    priority = NotificationPriority.P0,
                    payloadJson = payload,
                    status = NotificationStatus.PENDING,
                ),
            )
            // payload.alertType="ORDER_OUTBOX_DRAIN_FAILED" 가 운영 식별 키.
            // NotificationEventType enum과는 별도 — alertType은 payload schema, eventType은 DB 컬럼 schema.
            log.info(
                "drain failure 알림 INSERT 완료: outboxId={} alertType=ORDER_OUTBOX_DRAIN_FAILED priority=P0",
                outbox.id,
            )
        }.onFailure { e ->
            log.error(
                "drain failure 알림 INSERT 실패: outboxId={} err={}",
                outbox.id,
                e.message,
            )
        }
    }

    /**
     * 자본 path 보호 — KillSwitch auto-ON (ADR 0043 정합).
     *
     * partitionKey == userId 컨벤션이므로 그 값을 그대로 engage. partitionKey null 시 skip.
     */
    private fun engageKillSwitchSafely(outbox: OrderEventOutbox) {
        val userId = outbox.partitionKey
        if (userId.isNullOrBlank()) {
            log.warn(
                "drain FAILED outbox에 partitionKey(userId) 없음 — KillSwitch skip outboxId={}",
                outbox.id,
            )
            return
        }
        val port = killSwitchTogglePortProvider.getIfAvailable()
        if (port == null) {
            log.warn(
                "KillSwitchTogglePort 미등록 — drain FAILED 후속 engage skip outboxId={} userId={}",
                outbox.id,
                userId,
            )
            return
        }
        runCatching { port.engage(userId, KILLSWITCH_REASON) }
            .onSuccess {
                log.error(
                    "KillSwitch auto-ON (drain FAILED): outboxId={} userId={} reason={}",
                    outbox.id,
                    userId,
                    KILLSWITCH_REASON,
                )
            }.onFailure { e ->
                log.error(
                    "KillSwitch engage 실패 — 자본 사고 노출 risk: outboxId={} userId={} err={}",
                    outbox.id,
                    userId,
                    e.message,
                )
            }
    }
}

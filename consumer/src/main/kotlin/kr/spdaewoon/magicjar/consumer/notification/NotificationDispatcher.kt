package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
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
import java.time.Instant
import java.util.UUID

/**
 * T-NOTIFY 알림 Kafka 7 listener 통합 dispatcher (ADR 0044 + ADR 0070 §2-4 + spec §4 LOCK).
 *
 * ### 7 listen 매트릭스 (ADR 0070 §2-4)
 * | # | topic                  | priority | rate limit             |
 * |---|------------------------|----------|------------------------|
 * | 1 | order.filled.v1        | P0       | 즉시 (TRADE, REAL/MOCK/PAPER 공통) |
 * | 2 | order.rejected.v1      | P0       | 즉시 (TRADE, REAL/MOCK/PAPER 공통) |
 * | 3 | order.cancelled.v1     | P2       | 60분 윈도 ≥ 5건 시 1회   |
 * | 4 | risk.violation.v1      | P0       | 즉시 (rate limit 0)     |
 * | 5 | reconcile.drift.v1     | P0       | 즉시                     |
 * | 6 | killswitch.engaged.v1  | P0       | 즉시                     |
 * | 7 | reconcile.run.v1       | P2       | 즉시 (audit only)        |
 *
 * ### 처리 흐름
 * Kafka listen → buildPayload → dedup check → rate limit check → outbox INSERT (PENDING)
 * → SlackWebhookSender 동기 발송 (P0) or deferred (P1/P2)
 *
 * ### outbox graceful
 * NotificationOutboxRepository 미설정 시 log.warn + skip (Phase 2 JPA 합류 전 graceful).
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
@Component
class NotificationDispatcher(
    private val outboxRepositoryProvider: ObjectProvider<NotificationOutboxRepository>,
    private val rateLimiterProvider: ObjectProvider<NotificationRateLimiterPort>,
    private val slackSenderProvider: ObjectProvider<SlackWebhookSender>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(NotificationDispatcher::class.java)

    companion object {
        // P2 rate limit 임계값 (ADR 0070 §2-4)
        const val CANCELLED_THRESHOLD = 5

        const val WINDOW_60_MIN = 60

        const val MAX_RETRY_COUNT = 5
    }

    // --- 7 @KafkaListener ---

    @KafkaListener(topics = ["order.filled.v1"], groupId = "magicjar-notification-order-filled")
    fun onOrderFilled(record: ConsumerRecord<String, String>) = handleEvent(record, NotificationEventType.ORDER_FILLED, "order.filled")

    @KafkaListener(topics = ["order.rejected.v1"], groupId = "magicjar-notification-order-rejected")
    fun onOrderRejected(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.ORDER_REJECTED, "order.rejected")

    @KafkaListener(topics = ["order.cancelled.v1"], groupId = "magicjar-notification-order-cancelled")
    fun onOrderCancelled(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.ORDER_CANCELLED, "order.cancelled")

    @KafkaListener(topics = ["risk.violation.v1"], groupId = "magicjar-notification-risk-violation")
    fun onRiskViolation(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.RISK_VIOLATION, "risk.violation")

    @KafkaListener(topics = ["reconcile.drift.v1"], groupId = "magicjar-notification-reconcile-drift")
    fun onReconcileDrift(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.RECONCILE_DRIFT, "reconcile.drift")

    @KafkaListener(topics = ["killswitch.engaged.v1"], groupId = "magicjar-notification-killswitch")
    fun onKillSwitchEngaged(record: ConsumerRecord<String, String>) =
        handleEvent(record, NotificationEventType.KILLSWITCH_ENGAGED, "killswitch.engaged")

    @KafkaListener(topics = ["reconcile.run.v1"], groupId = "magicjar-notification-reconcile-run")
    fun onReconcileRun(record: ConsumerRecord<String, String>) = handleEvent(record, NotificationEventType.RECONCILE_RUN, "reconcile.run")

    // --- 공통 처리 ---

    internal fun handleEvent(
        record: ConsumerRecord<String, String>,
        eventType: NotificationEventType,
        topicHash: String,
    ) {
        try {
            val value = record.value() ?: return

            @Suppress("UNCHECKED_CAST")
            val raw = objectMapper.readValue(value, Map::class.java) as Map<String, Any?>

            val userId =
                raw["userId"]?.toString() ?: run {
                    log.warn("NotificationDispatcher userId null — topic={} offset={}", record.topic(), record.offset())
                    return
                }

            // eventId dedup: 수신한 eventId or 신규 생성
            val eventId =
                raw["eventId"]?.toString()?.let {
                    runCatching { UUID.fromString(it) }.getOrNull()
                } ?: UUID.randomUUID()

            // P1/P2 rate limit 체크
            if (!shouldEmit(userId, topicHash, eventType.defaultPriority)) {
                log.debug(
                    "NotificationDispatcher rate limit skip — userId={} type={} topicHash={}",
                    userId.take(4) + "****",
                    eventType,
                    topicHash,
                )
                return
            }

            val reason = buildReason(raw, eventType)
            val meta = buildMeta(raw, eventType)
            val payloadJson =
                objectMapper.writeValueAsString(
                    mapOf(
                        "eventId" to eventId.toString(),
                        "eventType" to eventType.name,
                        "userId" to userId,
                        "reason" to reason,
                        "meta" to meta,
                        "createdAt" to Instant.now().epochSecond,
                    ),
                )

            val outbox =
                NotificationOutbox(
                    eventId = eventId,
                    channel = NotificationChannel.SLACK,
                    priority = eventType.defaultPriority,
                    payloadJson = payloadJson,
                    status = NotificationStatus.PENDING,
                    createdAt = Instant.now(),
                )

            val outboxRepository = outboxRepositoryProvider.getIfAvailable()
            if (outboxRepository == null) {
                log.warn("NotificationOutboxRepository 미등록 — outbox INSERT skip. topic={} userId={}****", record.topic(), userId.take(4))
                // P0는 outbox 없어도 직접 Slack 시도
                if (eventType.defaultPriority == NotificationPriority.P0) {
                    tryDirectSlack(userId, reason, eventType, meta)
                }
                return
            }

            val saved = outboxRepository.save(outbox)
            log.info(
                "NotificationDispatcher outbox INSERT: id={} type={} priority={} userId={}****",
                saved.id,
                eventType,
                eventType.defaultPriority,
                userId.take(4),
            )

            // P0: 즉시 Slack 발송 시도
            if (eventType.defaultPriority == NotificationPriority.P0) {
                sendImmediate(saved, userId, reason, outboxRepository)
            }
        } catch (e: Exception) {
            log.error(
                "NotificationDispatcher 처리 실패: topic={} offset={} err={}",
                record.topic(),
                record.offset(),
                e.message,
                e,
            )
        }
    }

    // --- private helpers ---

    private fun shouldEmit(
        userId: String,
        topicHash: String,
        priority: NotificationPriority,
    ): Boolean {
        if (priority == NotificationPriority.P0) return true

        val rateLimiter = rateLimiterProvider.getIfAvailable() ?: return true // graceful: limiter 미등록 시 허용

        val (threshold, windowMin) =
            when (topicHash) {
                "order.cancelled" -> CANCELLED_THRESHOLD to WINDOW_60_MIN
                else -> 1 to WINDOW_60_MIN
            }
        return rateLimiter.shouldEmit(userId, topicHash, threshold, windowMin)
    }

    private fun sendImmediate(
        outbox: NotificationOutbox,
        userId: String,
        reason: String,
        outboxRepository: NotificationOutboxRepository,
    ) {
        val sender = slackSenderProvider.getIfAvailable()
        if (sender == null) {
            log.warn("SlackWebhookSender 미등록 — P0 즉시 발송 skip, outbox PENDING 유지: userId={}****", userId.take(4))
            return
        }
        val id = outbox.id ?: return
        try {
            val sent = sender.sendOutbox(outbox)
            if (sent) {
                outboxRepository.markSent(id)
                log.info("P0 즉시 Slack 발송 성공: outboxId={} userId={}****", id, userId.take(4))
            } else {
                outboxRepository.incrementRetryCount(id)
                log.warn("P0 즉시 Slack 발송 실패 — PENDING 유지: outboxId={} userId={}****", id, userId.take(4))
            }
        } catch (e: Exception) {
            log.error("P0 즉시 Slack 발송 예외: outboxId={} err={}", id, e.message)
            outboxRepository.incrementRetryCount(id)
        }
    }

    private fun tryDirectSlack(
        userId: String,
        reason: String,
        eventType: NotificationEventType,
        meta: Map<String, String?>,
    ) {
        val sender = slackSenderProvider.getIfAvailable() ?: return
        try {
            val message = NotificationFormatter.format(userId, eventType, reason, meta)
            sender.sendDirect(message)
        } catch (e: Exception) {
            log.error("direct Slack 발송 예외: userId={}**** err={}", userId.take(4), e.message)
        }
    }

    private fun buildReason(
        raw: Map<String, Any?>,
        eventType: NotificationEventType,
    ): String =
        when (eventType) {
            NotificationEventType.ORDER_FILLED ->
                "체결: ${accountModeLabel(raw)}${raw["code"]} ${raw["side"]} ${executedQuantity(raw)}주 @ ${raw["executedPrice"]}"
            NotificationEventType.ORDER_REJECTED ->
                "주문 거절: ${accountModeLabel(raw)}${raw["code"]} ${raw["side"]} — ${raw["reason"]}"
            NotificationEventType.ORDER_CANCELLED ->
                "주문 취소: ${accountModeLabel(raw)}${raw["code"]} ${raw["side"]} (주문번호 ${raw["kisOrderNo"]})"
            NotificationEventType.RISK_VIOLATION ->
                buildRiskReason(raw)
            NotificationEventType.RECONCILE_DRIFT ->
                "포지션 불일치: ${raw["symbol"]} KIS=${raw["kisQty"]}주 내부=${raw["internalQty"]}주 diff=${raw["qtyDiff"]}"
            NotificationEventType.KILLSWITCH_ENGAGED ->
                "KillSwitch 발동: reason=${raw["reason"]} triggeredBy=${raw["triggeredBy"]}"
            NotificationEventType.RECONCILE_RUN ->
                "잔고 점검 완료: status=${raw["status"]} driftCount=${raw["driftCount"]}"
            NotificationEventType.DLT_RECEIVED ->
                // 본 dispatcher가 직접 produce 안 함 — OrderDltMonitorListener 영역.
                // exhaustive when 정합용 분기 (실제 호출 path 0건).
                "Kafka DLT 수신: dltTopic=${raw["dltTopic"]} srcOffset=${raw["srcOffset"]}"
            NotificationEventType.SYSTEM_EVENT ->
                "시스템 이벤트: severity=${raw["severity"]} category=${raw["category"]} action=${raw["action"]} detail=${raw["detailJson"]}"
        }

    private fun accountModeLabel(raw: Map<String, Any?>): String {
        val mode = raw["accountType"] ?: raw["mode"] ?: raw["accountMode"]
        return mode?.toString()?.takeIf { it.isNotBlank() }?.let { "[$it] " } ?: ""
    }

    private fun executedQuantity(raw: Map<String, Any?>): Any? =
        raw["executedQuantity"]
            ?: raw["executedQty"]
            ?: raw["filledQuantity"]
            ?: raw["filledQty"]

    private fun buildMeta(
        raw: Map<String, Any?>,
        eventType: NotificationEventType,
    ): Map<String, String?> = raw.mapKeys { it.key }.mapValues { it.value?.toString() }

    private fun buildRiskReason(raw: Map<String, Any?>): String {
        val code =
            raw["code"]
                ?.toString()
                ?.takeIf { it.isNotBlank() }
                ?.let { "$it " }
                .orEmpty()
        val title = raw["alertTitle"]?.toString()?.takeIf { it.isNotBlank() }
        val message = raw["alertMessage"]?.toString()?.takeIf { it.isNotBlank() }
        return if (title != null || message != null) {
            "리스크 차단: $code${title ?: raw["rule"]} — ${message ?: raw["reason"]}"
        } else {
            "리스크 위반: ${raw["code"]} rule=${raw["rule"]} — ${raw["reason"]}"
        }
    }
}

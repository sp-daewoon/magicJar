package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.NotificationStatus
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant
import java.util.UUID

/**
 * NotificationDispatcher 단위 테스트 (spec §2-4 + ADR 0070 §2-4 LOCK).
 *
 * 7 케이스:
 *  (1) ORDER_FILLED → outbox INSERT PENDING
 *  (2) ORDER_REJECTED → outbox INSERT PENDING
 *  (3) ORDER_CANCELLED → outbox INSERT PENDING
 *  (4) RISK_VIOLATION (P0) → outbox INSERT + 즉시 발송 시도
 *  (5) RECONCILE_DRIFT (P0) → outbox INSERT + 즉시 발송 시도
 *  (6) KILLSWITCH_ENGAGED (P0) → outbox INSERT + 즉시 발송 시도
 *  (7) RECONCILE_RUN (P2) → rate limit 체크 후 outbox INSERT
 *
 * AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0070 §1-2).
 */
class NotificationDispatcherTest {
    private val objectMapper = ObjectMapper()
    private val outboxRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val rateLimiter = mockk<NotificationRateLimiterPort>()
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)

    private val outboxProvider = mockk<ObjectProvider<NotificationOutboxRepository>>()
    private val rateLimiterProvider = mockk<ObjectProvider<NotificationRateLimiterPort>>()
    private val slackSenderProvider = mockk<ObjectProvider<SlackWebhookSender>>()

    private val dispatcher: NotificationDispatcher =
        run {
            every { outboxProvider.getIfAvailable() } returns outboxRepository
            every { rateLimiterProvider.getIfAvailable() } returns rateLimiter
            every { slackSenderProvider.getIfAvailable() } returns slackSender
            // rate limit: P1/P2는 기본 emit 허용
            every { rateLimiter.shouldEmit(any(), any(), any(), any()) } returns true
            // outbox save: eventId 포함 응답
            every { outboxRepository.save(any()) } answers {
                val arg = it.invocation.args[0] as NotificationOutbox
                arg.copy(id = 100L)
            }
            NotificationDispatcher(outboxProvider, rateLimiterProvider, slackSenderProvider, objectMapper)
        }

    @Test
    fun `(1) ORDER_FILLED — outbox INSERT PENDING (P1)`() {
        val record = record("order.filled.v1", filledPayload("U1", "005930"))
        dispatcher.onOrderFilled(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P1)
        assertThat(slot.captured.status).isEqualTo(NotificationStatus.PENDING)
    }

    @Test
    fun `(2) ORDER_REJECTED — outbox INSERT PENDING (P1)`() {
        val record = record("order.rejected.v1", rejectedPayload("U1", "005930"))
        dispatcher.onOrderRejected(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P1)
    }

    @Test
    fun `(3) ORDER_CANCELLED — outbox INSERT PENDING (P2)`() {
        val record = record("order.cancelled.v1", cancelledPayload("U1", "005930"))
        dispatcher.onOrderCancelled(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P2)
    }

    @Test
    fun `(4) RISK_VIOLATION (P0) — outbox INSERT + 즉시 Slack 발송 시도`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record("risk.violation.v1", riskViolationPayload("U1"))
        dispatcher.onRiskViolation(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        // P0 즉시 발송 시도 → sender 호출
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(5) RECONCILE_DRIFT (P0) — outbox INSERT + 즉시 Slack 발송 시도`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record("reconcile.drift.v1", driftPayload("U1"))
        dispatcher.onReconcileDrift(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(6) KILLSWITCH_ENGAGED (P0) — outbox INSERT + 즉시 Slack 발송 시도`() {
        every { slackSender.sendOutbox(any()) } returns true
        val record = record("killswitch.engaged.v1", killSwitchPayload("U1"))
        dispatcher.onKillSwitchEngaged(record)

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `(7) RECONCILE_RUN (P2) — rate limit 체크 후 outbox INSERT`() {
        val record = record("reconcile.run.v1", reconcileRunPayload("U1"))
        dispatcher.onReconcileRun(record)

        verify { outboxRepository.save(any()) }
        verify { rateLimiter.shouldEmit("U1", "reconcile.run", any(), any()) }
    }

    // --- payload builders ---

    private fun record(
        topic: String,
        payload: String,
    ) = ConsumerRecord<String, String>(topic, 0, 0L, "U1", payload)

    private fun filledPayload(
        userId: String,
        code: String,
    ) =
        """{"eventId":"${UUID.randomUUID()}","orderId":"O1","userId":"$userId","code":"$code","side":"BUY","executedQuantity":10,"executedPrice":"75000","executedAt":${Instant.now().epochSecond}}"""

    private fun rejectedPayload(
        userId: String,
        code: String,
    ) = """{"eventId":"${UUID.randomUUID()}","orderId":"O2","userId":"$userId","code":"$code","side":"BUY","reason":"KILLSWITCH_ON"}"""

    private fun cancelledPayload(
        userId: String,
        code: String,
    ) = """{"eventId":"${UUID.randomUUID()}","orderId":"O3","userId":"$userId","code":"$code","side":"SELL","kisOrderNo":"K123"}"""

    private fun riskViolationPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","signalId":"S1","userId":"$userId","code":"005930","rule":"MAX_LOSS_PER_TRADE","reason":"손실 한도 초과"}"""

    private fun driftPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","runId":101,"userId":"$userId","symbol":"005930","kisQty":100,"internalQty":99,"qtyDiff":1}"""

    private fun killSwitchPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","userId":"$userId","reason":"POSITION_DRIFT","triggeredBy":"AUTO","engagedAt":${Instant.now().epochSecond}}"""

    private fun reconcileRunPayload(userId: String) =
        """{"eventId":"${UUID.randomUUID()}","runId":101,"userId":"$userId","status":"RECONCILED","driftCount":0}"""
}

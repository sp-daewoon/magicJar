package kr.spdaewoon.magicjar.consumer.notification

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.NotificationPriority
import kr.spdaewoon.magicjar.domain.notification.port.NotificationOutboxRepository
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Instant

class SystemEventNotificationDispatcherTest {
    private val objectMapper = ObjectMapper().registerKotlinModule().registerModule(JavaTimeModule())
    private val outboxRepository = mockk<NotificationOutboxRepository>(relaxed = true)
    private val rateLimiter = mockk<NotificationRateLimiterPort>()
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)

    private val outboxProvider = mockk<ObjectProvider<NotificationOutboxRepository>>()
    private val rateLimiterProvider = mockk<ObjectProvider<NotificationRateLimiterPort>>()
    private val slackSenderProvider = mockk<ObjectProvider<SlackWebhookSender>>()

    private val dispatcher =
        run {
            every { outboxProvider.getIfAvailable() } returns outboxRepository
            every { rateLimiterProvider.getIfAvailable() } returns rateLimiter
            every { slackSenderProvider.getIfAvailable() } returns slackSender
            every { rateLimiter.shouldEmit(any(), any(), any(), any()) } returns true
            every { outboxRepository.save(any()) } answers {
                val arg = it.invocation.args[0] as NotificationOutbox
                arg.copy(id = 200L)
            }
            SystemEventNotificationDispatcher(outboxProvider, rateLimiterProvider, slackSenderProvider, objectMapper)
        }

    @Test
    fun `ERROR system event inserts P0 outbox and sends Slack immediately`() {
        dispatcher.onSystemEvent(record(event(SystemEventSeverity.ERROR)))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P0)
        verify { slackSender.sendOutbox(any()) }
    }

    @Test
    fun `WARN system event uses grouped P2 outbox`() {
        dispatcher.onSystemEvent(record(event(SystemEventSeverity.WARN)))

        val slot = slot<NotificationOutbox>()
        verify { outboxRepository.save(capture(slot)) }
        assertThat(slot.captured.priority).isEqualTo(NotificationPriority.P2)
        verify(exactly = 0) { slackSender.sendOutbox(any()) }
        verify { rateLimiter.shouldEmit("broadcast", "system.websocket.ws_disconnected", 3, 10) }
    }

    @Test
    fun `INFO system event is not sent to Slack`() {
        dispatcher.onSystemEvent(record(event(SystemEventSeverity.INFO)))

        verify(exactly = 0) { outboxRepository.save(any()) }
        verify(exactly = 0) { slackSender.sendOutbox(any()) }
    }

    private fun record(event: SystemEvent): ConsumerRecord<String, String> =
        ConsumerRecord("system.event.v1", 0, 0L, "broadcast", objectMapper.writeValueAsString(event))

    private fun event(severity: SystemEventSeverity): SystemEvent =
        SystemEvent(
            category = SystemEventCategory.WEBSOCKET,
            severity = severity,
            actor = "KisWebSocketBridge",
            action = "WS_DISCONNECTED",
            userId = null,
            correlationId = "cid-1",
            detailJson = """{"reason":"test"}""",
            occurredAt = Instant.parse("2026-06-01T00:00:00Z"),
        )
}

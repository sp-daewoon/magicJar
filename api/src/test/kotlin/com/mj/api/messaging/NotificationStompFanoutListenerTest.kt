package com.mj.api.messaging

import com.mj.api.messaging.listener.NotificationStompFanoutListener
import com.mj.domain.notification.NotificationEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate

class NotificationStompFanoutListenerTest {
    private val stomp = mockk<SimpMessagingTemplate>(relaxed = true)
    private val listener = NotificationStompFanoutListener(stomp)

    @Test
    fun `notification → STOMP topic notifications fanout`() {
        every { stomp.convertAndSend(any<String>(), any<Any>()) } just Runs

        val event =
            NotificationEvent(
                level = NotificationEvent.LEVEL_WARN,
                category = NotificationEvent.CATEGORY_SUBSCRIPTION,
                title = "실시간 시세 한도 도달",
                body = "035420 종목 구독 해제됨",
            )

        listener.onNotification(event)

        verify(exactly = 1) { stomp.convertAndSend("/topic/notifications", event) }
    }
}

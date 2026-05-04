package com.mj.consumer.application.notification

import com.mj.consumer.messaging.producer.NotificationPublisher
import com.mj.domain.market.event.SubscriptionEvictedEvent
import com.mj.domain.notification.NotificationEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

class SubscriptionEvictedNotificationAdapterTest {
    private val publisher = mockk<NotificationPublisher>()
    private val adapter = SubscriptionEvictedNotificationAdapter(publisher)

    @Test
    fun `evicted 이벤트 → notification publish`() {
        val captured = slot<NotificationEvent>()
        every { publisher.publish(capture(captured)) } just Runs

        adapter.onEvicted(SubscriptionEvictedEvent(evictedCode = "035420"))

        verify(exactly = 1) { publisher.publish(any()) }
        val n = captured.captured
        assert(n.level == NotificationEvent.LEVEL_WARN)
        assert(n.category == NotificationEvent.CATEGORY_SUBSCRIPTION)
        assert(n.title.contains("실시간 시세 한도"))
        assert(n.body.contains("035420"))
    }

    @Test
    fun `publisher 예외 — swallowed (메인 흐름 막지 않음)`() {
        every { publisher.publish(any()) } throws RuntimeException("kafka down")

        adapter.onEvicted(SubscriptionEvictedEvent(evictedCode = "004090"))
        // 예외 전파 안 됨
    }
}

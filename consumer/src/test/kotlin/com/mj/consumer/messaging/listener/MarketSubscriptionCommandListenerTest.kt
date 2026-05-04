package com.mj.consumer.messaging.listener

import com.mj.consumer.application.market.SubscriptionManager
import com.mj.domain.market.event.MarketSubscriptionCommandEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * MarketSubscriptionCommandListener 단위 테스트 — Quote hotfix B (R-B) + R2-A3 §T21.
 *
 * R2-A3 변경: KisWsRouter.subscribe 직접 → SubscriptionManager.addOnDemand 위임.
 */
class MarketSubscriptionCommandListenerTest {
    private val subscriptionManager = mockk<SubscriptionManager>()
    private val listener = MarketSubscriptionCommandListener(subscriptionManager)

    @Test
    fun `H0UNCNT0 명령 수신 시 SubscriptionManager addOnDemand 호출`() {
        every { subscriptionManager.addOnDemand("005930") } just Runs

        listener.onCommand(MarketSubscriptionCommandEvent(code = "005930"))

        verify(exactly = 1) { subscriptionManager.addOnDemand("005930") }
    }

    @Test
    fun `blank 코드는 skip — manager 미호출`() {
        listener.onCommand(MarketSubscriptionCommandEvent(code = ""))

        verify(exactly = 0) { subscriptionManager.addOnDemand(any()) }
    }

    @Test
    fun `미지원 trId는 skip — manager 미호출`() {
        listener.onCommand(MarketSubscriptionCommandEvent(code = "005930", trId = "H0UNASP0"))

        verify(exactly = 0) { subscriptionManager.addOnDemand(any()) }
    }

    @Test
    fun `manager 예외는 swallowed — 컨슈머 retry 폭주 방지`() {
        every { subscriptionManager.addOnDemand("005930") } throws RuntimeException("ws down")

        // 예외 전파 안 됨
        listener.onCommand(MarketSubscriptionCommandEvent(code = "005930"))
    }
}

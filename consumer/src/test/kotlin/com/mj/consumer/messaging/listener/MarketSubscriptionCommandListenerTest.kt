package com.mj.consumer.messaging.listener

import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.domain.market.event.MarketSubscriptionCommandEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * MarketSubscriptionCommandListener 단위 테스트 — Quote hotfix B (R-B).
 */
class MarketSubscriptionCommandListenerTest {
    private val router = mockk<KisWsRouter>()
    private val quoteAdapter = mockk<KisQuoteWsAdapter>()
    private val listener = MarketSubscriptionCommandListener(router, quoteAdapter)

    @Test
    fun `H0UNCNT0 명령 수신 시 router subscribe 호출`() {
        every { router.subscribe(any(), any(), any()) } just Runs

        listener.onCommand(MarketSubscriptionCommandEvent(code = "005930"))

        verify(exactly = 1) { router.subscribe("H0UNCNT0", "005930", quoteAdapter) }
    }

    @Test
    fun `blank 코드는 skip — router 미호출`() {
        listener.onCommand(MarketSubscriptionCommandEvent(code = ""))

        verify(exactly = 0) { router.subscribe(any(), any(), any()) }
    }

    @Test
    fun `미지원 trId는 skip — router 미호출`() {
        listener.onCommand(MarketSubscriptionCommandEvent(code = "005930", trId = "H0UNASP0"))

        verify(exactly = 0) { router.subscribe(any(), any(), any()) }
    }

    @Test
    fun `router 예외는 swallowed — 컨슈머 retry 폭주 방지`() {
        every { router.subscribe(any(), any(), any()) } throws RuntimeException("ws down")

        // 예외 전파 안 됨
        listener.onCommand(MarketSubscriptionCommandEvent(code = "005930"))
    }
}

package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisWsRouter
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * KisQuoteSubscriber 단위 테스트 — ADR 0049 정합 (mount-only bootstrap).
 *
 * 부팅 시 자동 구독 0건. router.start()만 호출.
 * 사용자 진입 시 add는 MarketSubscriptionCommandListener·SubscriptionManager 책임.
 */
class KisQuoteSubscriberTest {
    private val router = mockk<KisWsRouter>(relaxed = true)

    @Test
    fun `onReady — router start 호출 (자동 구독 0건)`() {
        val subscriber = KisQuoteSubscriber(router)

        subscriber.onReady()

        verify(exactly = 1) { router.start() }
        // SubscriptionManager.bootstrap·router.subscribe 호출 0건 — 사용자 진입 전까지 비어 있다
        verify(exactly = 0) { router.subscribe(any(), any(), any()) }
    }

    @Test
    fun `onReady 두 번 호출해도 router-start 멱등 위임`() {
        val subscriber = KisQuoteSubscriber(router)

        subscriber.onReady()
        subscriber.onReady()

        // router.start() 자체는 두 번 호출되지만 KisWsRouter 안에서 멱등 처리 (기존 KisWsRouterTest 보장)
        verify(exactly = 2) { router.start() }
    }
}

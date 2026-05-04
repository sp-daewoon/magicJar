package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisIndexWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.domain.market.event.IndexTickEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * KisIndexSubscriber 단위 테스트 — plan T2-R2-A2 §T14.
 */
class KisIndexSubscriberTest {
    private val router = mockk<KisWsRouter>()
    private val indexAdapter = mockk<KisIndexWsAdapter>(relaxed = true)

    @Test
    fun `onReady — KOSPI·KOSDAQ 자동 구독`() {
        every { router.subscribe(any(), any(), any()) } just Runs
        val subscriber = KisIndexSubscriber(router, indexAdapter)

        subscriber.onReady()

        verify(exactly = 1) { router.subscribe(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSPI, indexAdapter) }
        verify(exactly = 1) { router.subscribe(KisIndexWsAdapter.TR_ID, IndexTickEvent.KOSDAQ, indexAdapter) }
    }
}

package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.consumer.messaging.producer.MarketCandleBackfillCommandPublisher
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * DailyCandleBackfillScheduler 단위 테스트 — ADR 0049 §2-C (activeCodes 기반).
 */
class DailyCandleBackfillSchedulerTest {
    private val publisher = mockk<MarketCandleBackfillCommandPublisher>()
    private val router = mockk<KisWsRouter>(relaxed = true)
    private val quoteAdapter = mockk<KisQuoteWsAdapter>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private fun newManager(): SubscriptionManager {
        every { router.subscribe(any(), any(), any()) } just Runs
        every { router.unsubscribe(any(), any()) } just Runs
        return SubscriptionManager(router, quoteAdapter, eventPublisher, maxSubscriptions = 18)
    }

    @Test
    fun `runDaily — activeCodes 비어 있으면 publisher 미호출`() {
        val mgr = newManager()
        val scheduler = DailyCandleBackfillScheduler(mgr, publisher)

        scheduler.runDaily()

        verify(exactly = 0) { publisher.ensureBackfill(any()) }
    }

    @Test
    fun `runDaily — activeCodes N건이면 각 종목 ensureBackfill 호출`() {
        every { publisher.ensureBackfill(any()) } just Runs
        val mgr = newManager()
        listOf("005930", "035420", "004090").forEach { mgr.addOnDemand(it) }
        val scheduler = DailyCandleBackfillScheduler(mgr, publisher)

        scheduler.runDaily()

        verify(exactly = 1) { publisher.ensureBackfill("005930") }
        verify(exactly = 1) { publisher.ensureBackfill("035420") }
        verify(exactly = 1) { publisher.ensureBackfill("004090") }
    }

    @Test
    fun `runDaily — 한 종목 publish 실패해도 나머지 진행`() {
        val mgr = newManager()
        listOf("005930", "035420").forEach { mgr.addOnDemand(it) }
        every { publisher.ensureBackfill("005930") } throws RuntimeException("kafka down")
        every { publisher.ensureBackfill("035420") } just Runs
        val scheduler = DailyCandleBackfillScheduler(mgr, publisher)

        // 예외 전파되지 않아야 한다
        scheduler.runDaily()

        verify(exactly = 1) { publisher.ensureBackfill("035420") }
    }
}

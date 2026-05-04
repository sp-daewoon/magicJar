package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisOrderbookWsAdapter
import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.domain.market.event.SubscriptionEvictedEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.context.ApplicationEventPublisher

/**
 * SubscriptionManager 단위 테스트 — ADR 0049 정합 (default5 면제 폐기, 모든 종목 동일 priority).
 */
class SubscriptionManagerTest {
    private val router = mockk<KisWsRouter>()
    private val quoteAdapter = mockk<KisQuoteWsAdapter>()
    private val orderbookAdapter = mockk<KisOrderbookWsAdapter>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)

    private fun newManager(maxSubs: Int = 18): SubscriptionManager {
        every { router.subscribe(any(), any(), any()) } just Runs
        every { router.unsubscribe(any(), any()) } just Runs
        return SubscriptionManager(
            router = router,
            quoteListener = quoteAdapter,
            orderbookListener = orderbookAdapter,
            eventPublisher = eventPublisher,
            maxSubscriptions = maxSubs,
        )
    }

    @Test
    fun `bootstrap — 빈 collection이면 router subscribe 0회 (mount-only 정책)`() {
        val mgr = newManager()
        mgr.bootstrap(emptyList())

        verify(exactly = 0) { router.subscribe(any(), any(), any()) }
        assert(mgr.activeSize() == 0)
    }

    @Test
    fun `bootstrap — 명시 collection이면 router subscribe + activeCodes 누적 (종목당 quote+orderbook 2 trId)`() {
        val mgr = newManager()
        mgr.bootstrap(listOf("005930", "000660", "035720"))

        verify(exactly = 3) { router.subscribe(KisQuoteWsAdapter.TR_ID, any(), quoteAdapter) }
        verify(exactly = 3) { router.subscribe(KisOrderbookWsAdapter.TR_ID, any(), orderbookAdapter) }
        assert(mgr.activeSize() == 3)
    }

    @Test
    fun `addOnDemand 신규 — quote+orderbook 둘 다 subscribe`() {
        val mgr = newManager()
        mgr.addOnDemand("035420")

        verify(exactly = 1) { router.subscribe(KisQuoteWsAdapter.TR_ID, "035420", quoteAdapter) }
        verify(exactly = 1) { router.subscribe(KisOrderbookWsAdapter.TR_ID, "035420", orderbookAdapter) }
        assert("035420" in mgr.activeCodes())
    }

    @Test
    fun `addOnDemand 멱등 — 같은 코드 재호출 시 router 호출 1회씩만 (각 trId)`() {
        val mgr = newManager()
        mgr.addOnDemand("035420")
        mgr.addOnDemand("035420")
        mgr.addOnDemand("035420")

        verify(exactly = 1) { router.subscribe(KisQuoteWsAdapter.TR_ID, "035420", quoteAdapter) }
        verify(exactly = 1) { router.subscribe(KisOrderbookWsAdapter.TR_ID, "035420", orderbookAdapter) }
    }

    @Test
    fun `LRU 한도 초과 — quote+orderbook 둘 다 unsubscribe + 이벤트 publish`() {
        val mgr = newManager(maxSubs = 5)
        // 5개 채움
        listOf("AAA111", "BBB222", "CCC333", "DDD444", "EEE555").forEach { mgr.addOnDemand(it) }
        assert(mgr.activeSize() == 5)

        // 6번째 진입 — LRU 가장 오래된 AAA111 evict
        mgr.addOnDemand("FFF666")

        assert(mgr.activeSize() == 5)
        verify(exactly = 1) { router.unsubscribe(KisQuoteWsAdapter.TR_ID, "AAA111") }
        verify(exactly = 1) { router.unsubscribe(KisOrderbookWsAdapter.TR_ID, "AAA111") }
        verify(exactly = 1) {
            eventPublisher.publishEvent(match<SubscriptionEvictedEvent> { it.evictedCode == "AAA111" })
        }
        assert("FFF666" in mgr.activeCodes())
        assert("AAA111" !in mgr.activeCodes())
    }

    @Test
    fun `addOnDemand로 LRU 갱신 — 재진입 종목은 MRU로 이동, 다른 종목이 quote+orderbook 둘 다 evict됨`() {
        val mgr = newManager(maxSubs = 3)
        mgr.addOnDemand("AAA111")
        mgr.addOnDemand("BBB222")
        mgr.addOnDemand("CCC333")

        // AAA111 재진입 → MRU로 이동 (이제 LRU는 BBB222)
        mgr.addOnDemand("AAA111")

        // 4번째 신규 → LRU = BBB222가 evict
        mgr.addOnDemand("DDD444")

        verify(exactly = 1) { router.unsubscribe(KisQuoteWsAdapter.TR_ID, "BBB222") }
        verify(exactly = 1) { router.unsubscribe(KisOrderbookWsAdapter.TR_ID, "BBB222") }
        assert("AAA111" in mgr.activeCodes())
        assert("BBB222" !in mgr.activeCodes())
    }

    @Test
    fun `router subscribe 예외 시 추적 set 에서 제거 — 멱등 재시도 가능`() {
        every { router.subscribe(any(), any(), any()) } throws RuntimeException("ws down")
        every { router.unsubscribe(any(), any()) } just Runs

        val mgr =
            SubscriptionManager(
                router = router,
                quoteListener = quoteAdapter,
                orderbookListener = orderbookAdapter,
                eventPublisher = eventPublisher,
                maxSubscriptions = 18,
            )
        mgr.addOnDemand("035420")

        // 실패 시 추적 set에 남지 않음
        assert("035420" !in mgr.activeCodes())
    }

    @Test
    fun `blank 코드 skip — router 미호출`() {
        val mgr = newManager()
        mgr.addOnDemand("")
        mgr.addOnDemand("   ")

        verify(exactly = 0) { router.subscribe(any(), any(), any()) }
    }
}

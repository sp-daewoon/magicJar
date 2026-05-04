package com.mj.consumer.application.market

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
 * SubscriptionManager 단위 테스트 — plan T2-R2-A3 §T21.
 *
 * 검증 시나리오:
 *   1. bootstrap — default5 + holdings 등록.
 *   2. addOnDemand — 신규 종목 add.
 *   3. addOnDemand 멱등 — 같은 코드 재호출 시 router subscribe 1회만.
 *   4. 18 한도 초과 — default5 외 LRU 가장 오래된 종목 evict + SubscriptionEvictedEvent publish.
 *   5. default5는 evict 면제.
 *   6. router 예외 시 추적 set rollback.
 *   7. blank 코드 skip.
 */
class SubscriptionManagerTest {
    private val router = mockk<KisWsRouter>()
    private val quoteAdapter = mockk<KisQuoteWsAdapter>()
    private val eventPublisher = mockk<ApplicationEventPublisher>(relaxed = true)
    private val defaultCodes = "005930,000660,035720,005380,051910"

    private fun newManager(maxSubs: Int = 18): SubscriptionManager {
        every { router.subscribe(any(), any(), any()) } just Runs
        every { router.unsubscribe(any(), any()) } just Runs
        return SubscriptionManager(
            router = router,
            quoteListener = quoteAdapter,
            eventPublisher = eventPublisher,
            defaultCodesRaw = defaultCodes,
            maxSubscriptions = maxSubs,
        )
    }

    @Test
    fun `bootstrap — 모든 default5 router subscribe 호출 + size 5`() {
        val mgr = newManager()
        mgr.bootstrap(listOf("005930", "000660", "035720", "005380", "051910"))

        verify(exactly = 5) { router.subscribe(KisQuoteWsAdapter.TR_ID, any(), quoteAdapter) }
        assert(mgr.activeSize() == 5)
    }

    @Test
    fun `addOnDemand 신규 — router subscribe + activeCodes 포함`() {
        val mgr = newManager()
        mgr.addOnDemand("035420")

        verify(exactly = 1) { router.subscribe(KisQuoteWsAdapter.TR_ID, "035420", quoteAdapter) }
        assert("035420" in mgr.activeCodes())
    }

    @Test
    fun `addOnDemand 멱등 — 같은 코드 재호출 시 router 호출 1회만`() {
        val mgr = newManager()
        mgr.addOnDemand("035420")
        mgr.addOnDemand("035420")
        mgr.addOnDemand("035420")

        verify(exactly = 1) { router.subscribe(KisQuoteWsAdapter.TR_ID, "035420", quoteAdapter) }
    }

    @Test
    fun `18 한도 — 19번째 신규 시 default5 외 가장 오래된 종목 evict + 이벤트 publish`() {
        val mgr = newManager(maxSubs = 18)
        // default5 + non-default 13개 = 18종목
        mgr.bootstrap(listOf("005930", "000660", "035720", "005380", "051910"))
        val nonDefault = (1..13).map { "00${100 + it}".takeLast(6) }
        nonDefault.forEach { mgr.addOnDemand(it) }
        assert(mgr.activeSize() == 18)

        // 19번째 신규 진입
        mgr.addOnDemand("999999")

        // size 유지 18
        assert(mgr.activeSize() == 18)
        // 가장 오래된 non-default(첫 번째 nonDefault[0])이 evict됨
        verify(exactly = 1) {
            router.unsubscribe(KisQuoteWsAdapter.TR_ID, nonDefault[0])
        }
        verify(exactly = 1) {
            eventPublisher.publishEvent(match<SubscriptionEvictedEvent> { it.evictedCode == nonDefault[0] })
        }
        assert("999999" in mgr.activeCodes())
        assert(nonDefault[0] !in mgr.activeCodes())
    }

    @Test
    fun `default5는 evict 면제 — 18 자리가 모두 default5+면제 종목이면 신규 add refuse`() {
        // 한도 5로 좁혀 default5만 채우고 신규 진입 시도
        val mgr = newManager(maxSubs = 5)
        mgr.bootstrap(listOf("005930", "000660", "035720", "005380", "051910"))
        assert(mgr.activeSize() == 5)

        mgr.addOnDemand("999999")

        // default5는 모두 면제 → evict 불가 → 신규 add refuse
        assert(mgr.activeSize() == 5)
        assert("999999" !in mgr.activeCodes())
        verify(exactly = 0) { router.unsubscribe(any(), any()) }
        verify(exactly = 0) { router.subscribe(KisQuoteWsAdapter.TR_ID, "999999", quoteAdapter) }
    }

    @Test
    fun `router subscribe 예외 시 추적 set 에서 제거 — 멱등 재시도 가능`() {
        every { router.subscribe(any(), any(), any()) } throws RuntimeException("ws down")
        every { router.unsubscribe(any(), any()) } just Runs

        val mgr =
            SubscriptionManager(
                router = router,
                quoteListener = quoteAdapter,
                eventPublisher = eventPublisher,
                defaultCodesRaw = defaultCodes,
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

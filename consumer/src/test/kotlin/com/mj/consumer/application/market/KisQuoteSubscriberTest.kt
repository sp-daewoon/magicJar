package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisWsRouter
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * KisQuoteSubscriber 단위 테스트.
 *
 * R2-A3 변경: KisWsRouter.subscribe 직접 → SubscriptionManager.bootstrap 위임.
 * verify 대상도 router.subscribe → subscriptionManager.bootstrap.
 *
 * plan §9-1 T5 + §5 T21 (2026-05-04-track2-r2-quote-ws.md).
 */
class KisQuoteSubscriberTest {
    private val router = mockk<KisWsRouter>(relaxed = true)
    private val subscriptionManager = mockk<SubscriptionManager>()

    private val defaultCodes = "005930,000660,035720,005380,051910"

    @Test
    fun `onReady 호출 시 router-start 후 default5 종목 bootstrap`() {
        val captured = slot<Collection<String>>()
        every { subscriptionManager.bootstrap(capture(captured)) } just Runs

        val subscriber =
            KisQuoteSubscriber(
                router = router,
                subscriptionManager = subscriptionManager,
                holdingsRepo = HoldingsCodeFetcher { emptyList() },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        verify(exactly = 1) { router.start() }
        verify(exactly = 1) { subscriptionManager.bootstrap(any()) }
        assert(captured.captured.toList() == listOf("005930", "000660", "035720", "005380", "051910")) {
            "expected default5 in order, got ${captured.captured}"
        }
    }

    @Test
    fun `holdings + default 합집합 distinct 후 bootstrap`() {
        val captured = slot<Collection<String>>()
        every { subscriptionManager.bootstrap(capture(captured)) } just Runs

        val subscriber =
            KisQuoteSubscriber(
                router = router,
                subscriptionManager = subscriptionManager,
                holdingsRepo = HoldingsCodeFetcher { listOf("005930", "035420") },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        // 005930(중복 제거됨), 035420 추가 → 6종목 unique
        assert(captured.captured.toSet() == setOf("005930", "000660", "035720", "005380", "051910", "035420")) {
            "expected 6 unique codes, got ${captured.captured}"
        }
    }

    @Test
    fun `18종목 한도 초과 시 take(18) 잘림`() {
        val captured = slot<Collection<String>>()
        every { subscriptionManager.bootstrap(capture(captured)) } just Runs

        val manyHoldings = (1..20).map { i -> String.format("%06d", i) }
        val subscriber =
            KisQuoteSubscriber(
                router = router,
                subscriptionManager = subscriptionManager,
                holdingsRepo = HoldingsCodeFetcher { manyHoldings },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        // 20 holdings + 5 default = 25 unique → take(18) = 18
        assert(captured.captured.size == 18) { "expected 18, got ${captured.captured.size}" }
    }

    @Test
    fun `holdings fetchHoldingCodes 예외 시 default5만 bootstrap`() {
        val captured = slot<Collection<String>>()
        every { subscriptionManager.bootstrap(capture(captured)) } just Runs

        val subscriber =
            KisQuoteSubscriber(
                router = router,
                subscriptionManager = subscriptionManager,
                holdingsRepo = HoldingsCodeFetcher { throw RuntimeException("DB error") },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        // 예외 → fallback → default5만
        assert(captured.captured.size == 5) { "expected 5, got ${captured.captured.size}" }
    }
}

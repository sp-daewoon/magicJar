package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test

/**
 * KisQuoteSubscriber 단위 테스트.
 *
 * plan §9-1 T5 (2026-05-04-track2-r2-quote-ws.md).
 */
class KisQuoteSubscriberTest {
    private val router = mockk<KisWsRouter>(relaxed = true)
    private val quoteListener = mockk<KisQuoteWsAdapter>(relaxed = true)

    private val defaultCodes = "005930,000660,035720,005380,051910"

    @Test
    fun `onReady 호출 시 router-start 후 default5 종목 subscribe`() {
        val subscriber =
            KisQuoteSubscriber(
                router = router,
                quoteListener = quoteListener,
                holdingsRepo = HoldingsCodeFetcher { emptyList() },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        verify(exactly = 1) { router.start() }
        verify(exactly = 5) { router.subscribe("H0UNCNT0", any(), quoteListener) }
    }

    @Test
    fun `holdings + default 합집합 distinct 후 subscribe`() {
        val subscriber =
            KisQuoteSubscriber(
                router = router,
                quoteListener = quoteListener,
                holdingsRepo = HoldingsCodeFetcher { listOf("005930", "035420") }, // 005930 중복 + 새 종목
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        // 005930(중복 제거됨), 035420 추가 → 6종목 unique
        verify(exactly = 6) { router.subscribe("H0UNCNT0", any(), quoteListener) }
    }

    @Test
    fun `18종목 한도 초과 시 take(18) 잘림`() {
        val manyHoldings = (1..20).map { i -> String.format("%06d", i) }
        val subscriber =
            KisQuoteSubscriber(
                router = router,
                quoteListener = quoteListener,
                holdingsRepo = HoldingsCodeFetcher { manyHoldings },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        // 20 holdings + 5 default = 25 unique → take(18) = 18
        verify(exactly = 18) { router.subscribe("H0UNCNT0", any(), quoteListener) }
    }

    @Test
    fun `holdings fetchHoldingCodes 예외 시 default5만 subscribe`() {
        val subscriber =
            KisQuoteSubscriber(
                router = router,
                quoteListener = quoteListener,
                holdingsRepo = HoldingsCodeFetcher { throw RuntimeException("DB error") },
                defaultCodesRaw = defaultCodes,
            )

        subscriber.onReady()

        // 예외 → fallback → default5만
        verify(exactly = 5) { router.subscribe("H0UNCNT0", any(), quoteListener) }
    }
}

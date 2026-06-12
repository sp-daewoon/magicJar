package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.mockk
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet

/**
 * OrderbookSubscriptionRouter 단위 테스트 — STRATEGY owner 전용 (2026-06-12 DETAIL 경로 제거 후).
 *
 * ## DETAIL 호가 구독 경로 제거 (anchor `detail-orderbook-removal`)
 * 종목 상세 진입 호가 구독(LRU 10 slot·debounce·idle timeout)은 제거됐다. 호가(H0STASP0)는
 * 전략 ACTIVE 경로(STRATEGY owner) 전용이다. 본 테스트는 그 경로의 회귀 0을 보증한다:
 * 1. 전략 ACTIVE → subscribeOrderbook
 * 2. 전략 비활성 → unsubscribeOrderbook + slot 회수
 * 3. 다중 전략 합집합 — 하나 빠져도 다른 전략 남으면 WS 유지
 */
class OrderbookSubscriptionRouterTest {
    private lateinit var mockKisWsPort: TestKisWsPort
    private lateinit var router: OrderbookSubscriptionRouter

    @BeforeEach
    fun setUp() {
        mockKisWsPort = TestKisWsPort()
        router = OrderbookSubscriptionRouter(mockKisWsPort, mockk(relaxed = true))
    }

    // ── STRATEGY owner 호가 구독 (전략 ACTIVE 전용) ────────────────────────────

    @Test
    fun `전략 ACTIVE 시 호가 구독 시작 (STRATEGY owner)`() {
        router.onStrategyActivated("093370", "strategy:86")

        assertTrue(mockKisWsPort.orderbookSubscribed.contains("093370"))
        assertTrue(router.current().contains("093370"))
        assertEquals(1, router.current().size)
    }

    @Test
    fun `전략 비활성 시 호가 구독 해제 (STRATEGY owner)`() {
        router.onStrategyActivated("093370", "strategy:86")
        assertTrue(router.current().contains("093370"))

        router.onStrategyDeactivated("093370", "strategy:86")

        assertFalse(router.current().contains("093370"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
        assertEquals(0, router.current().size)
    }

    @Test
    fun `같은 종목 여러 전략 demand는 합집합 — 하나 빠져도 다른 전략 남으면 WS 유지`() {
        router.onStrategyActivated("093370", "strategy:86")
        router.onStrategyActivated("093370", "strategy:99")
        // 멱등 + 합집합 — 구독은 1회만 발사
        assertEquals(1, mockKisWsPort.subscribeOrderbookCount)

        router.onStrategyDeactivated("093370", "strategy:86")
        // strategy:99 demand 남음 → WS 유지
        assertTrue(router.current().contains("093370"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("093370"))

        router.onStrategyDeactivated("093370", "strategy:99")
        // 마지막 demand 빠짐 → unsub
        assertFalse(router.current().contains("093370"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
    }

    @Test
    fun `같은 종목 동일 전략 재요청은 멱등 — 중복 subscribe 없음`() {
        router.onStrategyActivated("093370", "strategy:86")
        val subCountBefore = mockKisWsPort.subscribeOrderbookCount

        router.onStrategyActivated("093370", "strategy:86")

        assertEquals(subCountBefore, mockKisWsPort.subscribeOrderbookCount)
        assertEquals(1, router.current().size)
    }

    @Test
    fun `없는 전략 demand 비활성 호출은 no-op`() {
        router.onStrategyDeactivated("093370", "strategy:86")

        assertFalse(router.current().contains("093370"))
        assertFalse(mockKisWsPort.orderbookUnsubscribed.contains("093370"))
    }

    /**
     * 테스트용 KisWsPort mock (Mockito 대신 직접 구현 — 단순 추적)
     */
    class TestKisWsPort : KisWsPort {
        val orderbookSubscribed: MutableSet<String> = CopyOnWriteArraySet()
        val orderbookUnsubscribed: MutableSet<String> = CopyOnWriteArraySet()
        var subscribeOrderbookCount = 0

        override fun connect() {} // no-op stub — hotfix: KisWsPort.connect() 시그니처 추가

        override fun subscribe(codes: List<String>) {}

        override fun unsubscribe(codes: List<String>) {}

        override fun subscribedCodes(): Set<String> = emptySet()

        override fun subscribeOrderbook(codes: List<String>) {
            orderbookSubscribed.addAll(codes)
            subscribeOrderbookCount += codes.size
        }

        override fun unsubscribeOrderbook(codes: List<String>) {
            orderbookSubscribed.removeAll(codes.toSet())
            orderbookUnsubscribed.addAll(codes)
        }

        override fun subscribedOrderbookCodes(): Set<String> = orderbookSubscribed.toSet()
    }
}

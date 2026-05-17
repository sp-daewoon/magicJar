package kr.spdaewoon.magicjar.consumer.ingestion

import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet

/**
 * OrderbookSubscriptionRouter 단위 테스트 (ADR 0048 §2-1 LRU 10 slot).
 *
 * 검증 항목:
 * 1. 1번째 sub → KisWsPort.subscribeOrderbook 호출
 * 2. 11번째 sub → LRU evict + 새 종목 sub
 * 3. 페이지 unmount → unsubscribeOrderbook 호출 + slot 회수
 * 4. 200ms debounce — 동일 종목 빠른 재진입 무시
 */
class OrderbookSubscriptionRouterTest {
    private lateinit var mockKisWsPort: TestKisWsPort
    private lateinit var router: OrderbookSubscriptionRouter

    @BeforeEach
    fun setUp() {
        mockKisWsPort = TestKisWsPort()
        router = OrderbookSubscriptionRouter(mockKisWsPort)
    }

    @Test
    fun `1번째 종목 진입 시 subscribeOrderbook 호출`() {
        router.onSymbolDetailEntered("005930")

        assertTrue(mockKisWsPort.orderbookSubscribed.contains("005930"))
        assertEquals(1, router.current().size)
    }

    @Test
    fun `10종목 진입 시 모두 sub`() {
        repeat(10) { i ->
            router.onSymbolDetailEntered("code%02d".format(i))
        }

        assertEquals(10, router.current().size)
        assertEquals(10, mockKisWsPort.orderbookSubscribed.size)
    }

    @Test
    fun `11번째 종목 진입 시 LRU evict + 새 종목 sub`() {
        // 10개 진입
        val firstCode = "code00"
        router.onSymbolDetailEntered(firstCode)
        Thread.sleep(10) // 시간차 보장
        for (i in 1 until 10) {
            router.onSymbolDetailEntered("code%02d".format(i))
            Thread.sleep(5)
        }

        // 11번째
        router.onSymbolDetailEntered("code10")

        // slot은 여전히 10
        assertEquals(10, router.current().size)
        // 첫 번째가 evict됨
        assertFalse(router.current().contains(firstCode))
        // 11번째가 구독됨
        assertTrue(router.current().contains("code10"))
    }

    @Test
    fun `페이지 unmount 시 unsubscribeOrderbook + slot 회수`() {
        router.onSymbolDetailEntered("005930")
        assertTrue(router.current().contains("005930"))

        router.onSymbolDetailExited("005930")

        assertFalse(router.current().contains("005930"))
        assertTrue(mockKisWsPort.orderbookUnsubscribed.contains("005930"))
        assertEquals(0, router.current().size)
    }

    @Test
    fun `이미 구독 중인 종목 재진입 시 sub 중복 호출 없음`() {
        router.onSymbolDetailEntered("005930")
        val subCountBefore = mockKisWsPort.subscribeOrderbookCount

        router.onSymbolDetailEntered("005930")

        // 중복 subscribeOrderbook 호출 없음
        assertEquals(subCountBefore, mockKisWsPort.subscribeOrderbookCount)
        assertEquals(1, router.current().size)
    }

    /**
     * backlog §29 #3 — pendingDebounce expired entry 자동 cleanup 검증.
     *
     * 시나리오: 종목 1회 진입 후 재진입 없는 stale entry는 다음 진입 시 자동 정리.
     * 검증: 진입 → 200ms 대기 → 다른 종목 진입 시 stale entry 제거 확인.
     * pendingDebounce는 private이라 reflection으로 size 검증.
     */
    @Test
    fun `pendingDebounce expired entry는 다음 진입 시 자동 정리`() {
        router.onSymbolDetailEntered("000001") // entry 1 — pendingUntil = now + 200
        Thread.sleep(250) // 200ms debounce 만료 대기
        router.onSymbolDetailEntered("000002") // entry 2 — cleanup 트리거

        val debounceMap = readPendingDebounce(router)
        // entry 1은 expired로 cleanup, entry 2만 남아야 함
        assertFalse(debounceMap.containsKey("000001"), "expired entry 000001은 cleanup되어야 함")
        assertTrue(debounceMap.containsKey("000002"), "신규 entry 000002는 보존")
        assertEquals(1, debounceMap.size, "stale 미정리 시 size=2가 됨")
    }

    /**
     * backlog §29 #3 — unsubscribe 흐름(exit·LRU evict·idle timeout) 시
     * pendingDebounce 동기 정리 검증 — exit 경로.
     */
    @Test
    fun `onSymbolDetailExited 시 pendingDebounce 동기 정리`() {
        router.onSymbolDetailEntered("005930")
        assertTrue(readPendingDebounce(router).containsKey("005930"))

        router.onSymbolDetailExited("005930")

        assertFalse(readPendingDebounce(router).containsKey("005930"))
    }

    /**
     * backlog §29 #3 — LRU evict 시 evicted 종목 pendingDebounce 동기 정리.
     */
    @Test
    fun `LRU evict 시 pendingDebounce 동기 정리`() {
        val firstCode = "code00"
        router.onSymbolDetailEntered(firstCode)
        Thread.sleep(250) // debounce 만료 + 시간차 보장
        for (i in 1 until 10) {
            router.onSymbolDetailEntered("code%02d".format(i))
            Thread.sleep(5)
        }

        // 11번째 — firstCode가 LRU evict 됨
        router.onSymbolDetailEntered("code10")

        assertFalse(router.current().contains(firstCode), "LRU evict 검증")
        // pendingDebounce에서도 정리되어야 함 (또는 expired cleanup으로 정리)
        assertFalse(readPendingDebounce(router).containsKey(firstCode), "evicted 종목의 pendingDebounce 잔존 금지")
    }

    @Suppress("UNCHECKED_CAST")
    private fun readPendingDebounce(router: OrderbookSubscriptionRouter): Map<String, Long> {
        val field = OrderbookSubscriptionRouter::class.java.getDeclaredField("pendingDebounce")
        field.isAccessible = true
        return field.get(router) as Map<String, Long>
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

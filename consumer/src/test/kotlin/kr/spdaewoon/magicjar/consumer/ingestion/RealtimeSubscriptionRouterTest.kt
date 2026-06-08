package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.port.SubscriptionPolicy
import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArraySet

class RealtimeSubscriptionRouterTest {
    private lateinit var kisWsPort: TestKisWsPort
    private val pollingWorker = mockk<WatchlistPollingWorker>(relaxed = true)
    private val watchlistRepository = mockk<ConsumerWatchlistRepositoryAdapter>()
    private val sourceTracker = mockk<RedisSourceTrackerAdapter>(relaxed = true)
    private val todayCandleSeedPort = mockk<kr.spdaewoon.magicjar.domain.market.port.TodayCandleSeedPort>(relaxed = true)
    private lateinit var router: RealtimeSubscriptionRouter

    @BeforeEach
    fun setUp() {
        kisWsPort = TestKisWsPort()
        every { watchlistRepository.findAll() } returns emptyList()
        router =
            RealtimeSubscriptionRouter(
                kisWsPort = kisWsPort,
                pollingWorker = pollingWorker,
                watchlistRepository = watchlistRepository,
                sourceTracker = sourceTracker,
                todayCandleSeedPort = todayCandleSeedPort,
            )
    }

    @Test
    fun `DETAIL 진입은 watchlist 없이도 가격 WS를 구독한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.connected)
        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
    }

    @Test
    fun `DETAIL 이탈은 grace TTL 동안 가격 WS를 유지한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        router.release(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
        assertFalse(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    @Test
    fun `DETAIL 이탈 시 watchlist 종목이면 가격 WS를 유지한다`() {
        val now = Clock.System.now()
        every { watchlistRepository.findAll() } returns
            listOf(WatchlistEntry(id = 1L, userId = "user001", code = "005930", priority = 100, createdAt = now))

        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        router.release(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertFalse(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    @Test
    fun `전략 후보는 slot 부족 시 낮은 우선순위 watchlist를 polling으로 내린다`() {
        val subscribed = (1..SubscriptionPolicy.PRICE_TICK_MAX).map { "1%05d".format(it) }
        kisWsPort.priceSubscribed.addAll(subscribed)
        val now = Clock.System.now()
        every { watchlistRepository.findAll() } returns
            subscribed.mapIndexed { index, code ->
                WatchlistEntry(id = index.toLong() + 1L, userId = "user001", code = code, priority = 200 + index, createdAt = now)
            }

        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 40)

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        verify { pollingWorker.add(any()) }
    }

    @Test
    fun `HOLDING 등록은 가격 WS를 구독하고 최우선 demand로 추적한다 (ADR 0114 P3)`() {
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")

        assertTrue(kisWsPort.priceSubscribed.contains("005930"))
        assertTrue(router.isExternallyDemanded("005930"))
        // priority 0 (최우선).
        org.junit.jupiter.api.Assertions
            .assertEquals(0, router.priorityOf("005930"))
    }

    @Test
    fun `slot 포화 + 신규 진입 시 HOLDING 종목은 evict 후보에서 제외된다 (ADR 0114 P3 LOCK)`() {
        // 30 slot 중 1개를 HOLDING(보유 종목), 나머지는 낮은 우선순위 watchlist로 채운다.
        val holdingCode = "0HOLD0"
        val watchlistCodes = (1 until SubscriptionPolicy.PRICE_TICK_MAX).map { "1%05d".format(it) }
        kisWsPort.priceSubscribed.add(holdingCode)
        kisWsPort.priceSubscribed.addAll(watchlistCodes)
        val now = Clock.System.now()
        every { watchlistRepository.findAll() } returns
            watchlistCodes.mapIndexed { index, code ->
                WatchlistEntry(id = index.toLong() + 1L, userId = "user001", code = code, priority = 200 + index, createdAt = now)
            }

        // HOLDING demand 등록(이미 구독 중이라 재구독 X, demand만 추적).
        router.request(RealtimeDemandOwner.HOLDING, "holding:$holdingCode", holdingCode)
        // slot 포화 상태에서 신규 STRATEGY 진입 → evict가 일어나도 HOLDING은 절대 victim 아님.
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "005930", priority = 40)

        assertTrue(kisWsPort.priceSubscribed.contains(holdingCode))
        assertFalse(kisWsPort.priceUnsubscribed.contains(holdingCode))
    }

    @Test
    fun `HOLDING release는 즉시 demand를 제거한다 (전량 청산)`() {
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")
        assertTrue(router.isExternallyDemanded("005930"))

        router.release(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")

        assertFalse(router.isExternallyDemanded("005930"))
        assertTrue(kisWsPort.priceUnsubscribed.contains("005930"))
    }

    // ===== 구독 수립 시점 today-Hash 시드 (owner 무관 균일 — root cause fix 핵심) =====

    @Test
    fun `DETAIL 구독 수립 시 today-Hash 시드를 트리거한다`() {
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        // 시드는 lock 해제 후 비동기 — verify timeout으로 결과 대기.
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `WATCHLIST(HOLDING) 구독 수립 시 today-Hash 시드를 트리거한다`() {
        // HOLDING owner도 동일 Hash를 먹으므로 동일하게 시드 적용돼야 함.
        router.request(RealtimeDemandOwner.HOLDING, "holding:005930", "005930")
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    @Test
    fun `STRATEGY 후보 구독 수립 시 today-Hash 시드를 트리거한다`() {
        router.request(RealtimeDemandOwner.STRATEGY, "strategy:42", "000660", priority = 40)
        verify(timeout = 2000) { todayCandleSeedPort.seedTodayCandle("000660") }
    }

    @Test
    fun `이미 구독 중인 종목 재요청은 중복 시드를 트리거하지 않는다`() {
        kisWsPort.priceSubscribed.add("005930")
        router.request(RealtimeDemandOwner.DETAIL, "detail:user001", "005930")
        // 이미 구독 중 → ensureSubscribedLocked가 새 subscribe 발사 안 함 → 시드 트리거 0.
        Thread.sleep(300)
        verify(exactly = 0) { todayCandleSeedPort.seedTodayCandle("005930") }
    }

    class TestKisWsPort : KisWsPort {
        var connected = false
        val priceSubscribed: MutableSet<String> = CopyOnWriteArraySet()
        val priceUnsubscribed: MutableSet<String> = CopyOnWriteArraySet()

        override fun connect() {
            connected = true
        }

        override fun subscribe(codes: List<String>) {
            priceSubscribed.addAll(codes)
        }

        override fun unsubscribe(codes: List<String>) {
            priceSubscribed.removeAll(codes.toSet())
            priceUnsubscribed.addAll(codes)
        }

        override fun subscribedCodes(): Set<String> = priceSubscribed.toSet()

        override fun subscribeOrderbook(codes: List<String>) {}

        override fun unsubscribeOrderbook(codes: List<String>) {}

        override fun subscribedOrderbookCodes(): Set<String> = emptySet()
    }
}

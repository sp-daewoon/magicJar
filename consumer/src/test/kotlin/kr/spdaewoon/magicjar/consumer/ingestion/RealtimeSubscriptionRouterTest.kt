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

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
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * SubscriptionRouter smoke test (ADR 0048 §12-1-2 정합).
 * - 테스트 1: slot 여유 있음 → WS subscribe 호출
 * - 테스트 2: slot 가득 (30개 구독 중) + 새 종목 priority 더 낮음(숫자 큼) → polling 등록
 */
class SubscriptionRouterTest {
    private val kisWsPort = mockk<KisWsPort>(relaxed = true)
    private val pollingWorker = mockk<WatchlistPollingWorker>(relaxed = true)
    private val watchlistRepository = mockk<ConsumerWatchlistRepositoryAdapter>()
    private val sourceTracker = mockk<RedisSourceTrackerAdapter>(relaxed = true)
    private lateinit var realtimeSubscriptionRouter: RealtimeSubscriptionRouter

    private lateinit var router: SubscriptionRouter

    @BeforeEach
    fun setUp() {
        realtimeSubscriptionRouter =
            RealtimeSubscriptionRouter(
                kisWsPort = kisWsPort,
                pollingWorker = pollingWorker,
                watchlistRepository = watchlistRepository,
                sourceTracker = sourceTracker,
            )
        router =
            SubscriptionRouter(
                kisWsPort = kisWsPort,
                pollingWorker = pollingWorker,
                watchlistRepository = watchlistRepository,
                sourceTracker = sourceTracker,
                realtimeSubscriptionRouter = realtimeSubscriptionRouter,
            )
    }

    @Test
    fun `slot 여유 있음 - WS subscribe 호출`() {
        // 현재 구독 5개 → 여유 25개
        every { kisWsPort.subscribedCodes() } returns (1..5).map { "00${it}000" }.toSet()
        every { watchlistRepository.findAll() } returns emptyList()

        router.add("user001", "005930", priority = 100)

        verify { kisWsPort.subscribe(listOf("005930")) }
        verify { sourceTracker.set("user001", "005930", "live") }
    }

    @Test
    fun `slot 가득 + 새 종목 priority 더 낮음 - polling 등록`() {
        // 현재 구독 30개 (slot 가득 — PRICE_TICK_MAX=30)
        val subscribedCodes = (1..SubscriptionPolicy.PRICE_TICK_MAX).map { "CODE$it" }.toSet()
        every { kisWsPort.subscribedCodes() } returns subscribedCodes

        // 구독 중인 모든 종목이 priority=50 (새 종목 priority=200보다 우선순위 높음)
        val now = Clock.System.now()
        val watchlistEntries =
            subscribedCodes.mapIndexed { idx, code ->
                WatchlistEntry(id = idx.toLong() + 1, userId = "user001", code = code, priority = 50, createdAt = now)
            }
        every { watchlistRepository.findAll() } returns watchlistEntries

        // 새 종목 priority=200 → eviction 후보 없음 (모든 구독 종목이 더 높은 우선순위) → polling
        router.add("user001", "005930", priority = 200)

        verify { pollingWorker.add("005930") }
        verify { sourceTracker.set("user001", "005930", "polling") }
    }
}

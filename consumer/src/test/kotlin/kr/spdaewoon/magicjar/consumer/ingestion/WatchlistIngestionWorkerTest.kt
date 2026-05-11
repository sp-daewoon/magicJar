package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import io.mockk.verifyOrder
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerWatchlistRepositoryAdapter
import kr.spdaewoon.magicjar.domain.market.port.KisWsPort
import kr.spdaewoon.magicjar.domain.market.session.MarketSession
import kr.spdaewoon.magicjar.domain.market.session.MarketSessionResolver
import kr.spdaewoon.magicjar.domain.watchlist.WatchlistEntry
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * WatchlistIngestionWorker 단위 테스트.
 *
 * 핵심 검증:
 * - 부팅 시 watchlist 있으면 connect() 호출 후 recompute() 호출 (순서 보장)
 * - 부팅 시 watchlist 없으면 connect() 미호출
 * - connect() → subscribe() 순서 보장 (sendSink null 방지)
 *
 * hotfix 배경: KIS WS connect() 누락으로 실시간 시세 0건 (사용자 dogfooding 2026-05-11).
 * ADR 0028 §2-1: WatchlistIngestionWorker가 KIS WS 연결·구독의 단일 책임자.
 */
class WatchlistIngestionWorkerTest {
    private val subscriptionRouter = mockk<SubscriptionRouter>(relaxed = true)
    private val pollingWorker = mockk<WatchlistPollingWorker>(relaxed = true)
    private val kisWsPort = mockk<KisWsPort>(relaxed = true)
    private val watchlistRepository = mockk<ConsumerWatchlistRepositoryAdapter>()
    private val marketSessionResolver = mockk<MarketSessionResolver>()

    private lateinit var worker: WatchlistIngestionWorker

    @BeforeEach
    fun setUp() {
        worker =
            WatchlistIngestionWorker(
                subscriptionRouter = subscriptionRouter,
                pollingWorker = pollingWorker,
                kisWsPort = kisWsPort,
                watchlistRepository = watchlistRepository,
                marketSessionResolver = marketSessionResolver,
            )
    }

    @Test
    fun `watchlist 있으면 onStartup 시 connect() 호출 후 recompute() 호출`() {
        // given
        val entries =
            listOf(
                WatchlistEntry(userId = "user001", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)),
                WatchlistEntry(userId = "user001", code = "000660", priority = 200, createdAt = Instant.fromEpochSeconds(0)),
            )
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Closed(reason = "test")

        // when
        worker.onStartup()

        // then — connect() 반드시 1회 호출
        verify(exactly = 1) { kisWsPort.connect() }
        // then — recompute() 호출 (user001 대상)
        verify { subscriptionRouter.recompute("user001") }
    }

    @Test
    fun `connect() 는 recompute() 보다 반드시 먼저 호출된다`() {
        // given
        val entries = listOf(WatchlistEntry(userId = "user002", code = "035720", priority = 100, createdAt = Instant.fromEpochSeconds(0)))
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Closed(reason = "test")

        // when
        worker.onStartup()

        // then — 순서 검증 (connect → recompute, sendSink null 방지)
        verifyOrder {
            kisWsPort.connect()
            subscriptionRouter.recompute("user002")
        }
    }

    @Test
    fun `watchlist 없으면 connect() 미호출`() {
        // given — 빈 watchlist
        every { watchlistRepository.findAll() } returns emptyList()

        // when
        worker.onStartup()

        // then
        verify(exactly = 0) { kisWsPort.connect() }
        verify(exactly = 0) { subscriptionRouter.recompute(any()) }
    }

    @Test
    fun `장중(Regular) 세션이면 pollingWorker start() 호출`() {
        // given
        val entries = listOf(WatchlistEntry(userId = "user003", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)))
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Regular

        // when
        worker.onStartup()

        // then
        verify { pollingWorker.start() }
    }

    @Test
    fun `장마감(Closed) 세션이면 pollingWorker start() 미호출`() {
        // given
        val entries = listOf(WatchlistEntry(userId = "user004", code = "005930", priority = 100, createdAt = Instant.fromEpochSeconds(0)))
        every { watchlistRepository.findAll() } returns entries
        every { marketSessionResolver.resolve(any()) } returns MarketSession.Closed(reason = "closed")

        // when
        worker.onStartup()

        // then
        verify(exactly = 0) { pollingWorker.start() }
    }
}

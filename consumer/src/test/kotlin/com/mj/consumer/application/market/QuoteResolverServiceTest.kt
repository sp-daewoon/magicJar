package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.cache.InMemoryQuoteCacheAdapter
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.QuoteSource
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * QuoteResolverService 단위 테스트. spec §5, ADR 0033.
 *
 * 3-tier fallback 4 시나리오:
 *   S1 - cache miss + candle miss  → null
 *   S2 - cache hit (fresh/LIVE)    → source=LIVE, stale=false
 *   S3 - cache hit (stale/CACHED)  → source=CACHED, stale=true
 *   S4 - cache miss + candle hit   → source=LAST_DAY_CLOSE, stale=true
 */
class QuoteResolverServiceTest {
    private lateinit var cache: InMemoryQuoteCacheAdapter
    private lateinit var candleRepository: CandleRepository
    private lateinit var sessionResolver: MarketSessionResolver
    private lateinit var service: QuoteResolverService

    @BeforeEach
    fun setUp() {
        cache = InMemoryQuoteCacheAdapter()
        cache.clear()
        candleRepository = mockk()
        sessionResolver = mockk()

        every { sessionResolver.resolve() } returns openSession()

        service = QuoteResolverService(cache, candleRepository, sessionResolver)
    }

    @Test
    fun `S1 - cache miss + candle miss - null 반환`() {
        every { candleRepository.findLastClose("999999", any()) } returns null

        val result = service.resolve("999999")
        result shouldBe null
    }

    @Test
    fun `S2 - cache hit fresh - LIVE 반환`() {
        cache.putLatest("005930", BigDecimal("75000"), 1_000_000L, Instant.now(), "KIS_WS")

        val result = service.resolve("005930")

        result shouldNotBe null
        result!!.source shouldBe QuoteSource.LIVE
        result.stale shouldBe false
        result.price shouldBe BigDecimal("75000")
    }

    @Test
    fun `S3 - cache hit stale - CACHED 반환`() {
        val staleInstant = Instant.now().minusSeconds(15)
        cache.putLatest("005930", BigDecimal("74000"), null, staleInstant, "KIS_WS")

        val result = service.resolve("005930")

        result shouldNotBe null
        result!!.source shouldBe QuoteSource.CACHED
        result.stale shouldBe true
        result.price shouldBe BigDecimal("74000")
    }

    @Test
    fun `S4 - cache miss + candle hit - LAST_DAY_CLOSE 반환`() {
        val lastClose = makeCandle("005930", BigDecimal("73000"), Instant.parse("2026-05-01T00:00:00Z"))
        every { candleRepository.findLastClose("005930", any()) } returns lastClose

        val result = service.resolve("005930")

        result shouldNotBe null
        result!!.source shouldBe QuoteSource.LAST_DAY_CLOSE
        result.stale shouldBe true
        result.price shouldBe BigDecimal("73000")
    }

    @Test
    fun `putLatest - cache에 저장된다`() {
        val tick =
            com.mj.domain.market.event.TickEvent(
                code = "000660",
                at = Instant.now(),
                price = BigDecimal("120000"),
                volume = 500_000L,
                source = "KIS_WS",
            )
        service.putLatest(tick)

        val result = service.resolve("000660")
        result shouldNotBe null
        result!!.price shouldBe BigDecimal("120000")
        result.source shouldBe QuoteSource.LIVE
    }

    @Test
    fun `sessionResolver 오류 시 오늘 날짜로 fallback`() {
        every { sessionResolver.resolve() } throws RuntimeException("DB 연결 실패")
        every { candleRepository.findLastClose(any(), any()) } returns null

        // 예외 없이 null 반환 (오늘 날짜 fallback 사용)
        val result = service.resolve("005930")
        result shouldBe null
    }

    private fun openSession(): MarketSession {
        val zone = ZoneId.of("Asia/Seoul")
        return MarketSession(
            state = MarketSessionState.Open,
            kstAt = OffsetDateTime.now(zone),
            lastBusinessDate = LocalDate.now(zone),
            nextOpenAt = null,
        )
    }

    private fun makeCandle(
        code: String,
        close: BigDecimal,
        openTime: Instant,
    ) = Candle(
        code = code,
        interval = Interval.D1,
        openTime = openTime,
        open = close,
        high = close,
        low = close,
        close = close,
        volume = 10_000_000L,
        tradeCount = 100_000L,
    )
}

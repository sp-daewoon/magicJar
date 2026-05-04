package com.mj.consumer.application.market

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.QuoteCache
import com.mj.domain.market.cache.port.QuoteSnapshot
import com.mj.domain.market.event.TickEvent
import com.mj.domain.market.port.CandleRepository
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * TickFallbackEmitter 단위 테스트 — plan T2-R2-A3 §T19 + ADR 0033.
 */
class TickFallbackEmitterTest {
    private val subscriptionManager = mockk<SubscriptionManager>()
    private val sessionResolver = mockk<MarketSessionResolver>()
    private val quoteCache = mockk<QuoteCache>()
    private val candleRepository = mockk<CandleRepository>()
    private val tickPublisher = mockk<TickPublisher>(relaxed = true)

    private val emitter =
        TickFallbackEmitter(
            subscriptionManager = subscriptionManager,
            sessionResolver = sessionResolver,
            quoteCache = quoteCache,
            candleRepository = candleRepository,
            tickPublisher = tickPublisher,
        )

    private val today: LocalDate = LocalDate.of(2026, 5, 4)
    private val openSession =
        MarketSession(
            state = MarketSessionState.Open,
            kstAt = OffsetDateTime.of(today.atTime(11, 0), ZoneOffset.ofHours(9)),
            lastBusinessDate = today,
            nextOpenAt = null,
        )
    private val closedSession =
        MarketSession(
            state = MarketSessionState.Closed("Saturday"),
            kstAt = OffsetDateTime.of(today.atTime(20, 0), ZoneOffset.ofHours(9)),
            lastBusinessDate = today.minusDays(1),
            nextOpenAt = null,
        )

    @Test
    fun `Open 정규장 + 30초 이내 라이브 tick — emit 0`() {
        every { subscriptionManager.activeCodes() } returns setOf("005930")
        every { sessionResolver.resolve() } returns openSession
        every { quoteCache.getLatest("005930") } returns
            QuoteSnapshot(
                code = "005930",
                price = BigDecimal("75000"),
                volume = 100,
                asOf = Instant.now().minusSeconds(5), // 5초 전
                source = "KIS_WS",
                stale = false,
            )

        emitter.emit()

        verify(exactly = 0) { tickPublisher.publish(any()) }
    }

    @Test
    fun `Open + 30초 이상 라이브 tick 끊김 — cache stale tick emit`() {
        every { subscriptionManager.activeCodes() } returns setOf("005930")
        every { sessionResolver.resolve() } returns openSession
        every { quoteCache.getLatest("005930") } returns
            QuoteSnapshot(
                code = "005930",
                price = BigDecimal("75000"),
                volume = 100,
                asOf = Instant.now().minusSeconds(60), // 60초 전 = 30초 초과
                source = "KIS_WS",
                stale = false,
            )

        val captured = slot<TickEvent>()
        every { tickPublisher.publish(capture(captured)) } just Runs

        emitter.emit()

        verify(exactly = 1) { tickPublisher.publish(any()) }
        assert(captured.captured.code == "005930")
        assert(captured.captured.stale)
        assert(captured.captured.source == "CACHED")
    }

    @Test
    fun `Closed + cache 있음 — cache stale tick emit`() {
        every { subscriptionManager.activeCodes() } returns setOf("005930")
        every { sessionResolver.resolve() } returns closedSession
        every { quoteCache.getLatest("005930") } returns
            QuoteSnapshot(
                code = "005930",
                price = BigDecimal("74500"),
                volume = 50,
                asOf = Instant.parse("2026-05-03T06:00:00Z"),
                source = "KIS_WS",
                stale = true,
            )

        val captured = slot<TickEvent>()
        every { tickPublisher.publish(capture(captured)) } just Runs

        emitter.emit()

        verify(exactly = 1) { tickPublisher.publish(any()) }
        assert(captured.captured.source == "CACHED")
        assert(captured.captured.stale)
        assert(captured.captured.price == BigDecimal("74500"))
    }

    @Test
    fun `Closed + cache 없음 + last-day-close 있음 — last-day-close emit`() {
        every { subscriptionManager.activeCodes() } returns setOf("005930")
        every { sessionResolver.resolve() } returns closedSession
        every { quoteCache.getLatest("005930") } returns null
        every { candleRepository.findLastClose("005930", closedSession.lastBusinessDate) } returns
            Candle(
                code = "005930",
                interval = Interval.D1,
                openTime = Instant.parse("2026-05-03T00:00:00Z"),
                open = BigDecimal("73000"),
                high = BigDecimal("75000"),
                low = BigDecimal("72500"),
                close = BigDecimal("74000"),
                volume = 12_000_000L,
                tradeCount = 50_000L,
            )

        val captured = slot<TickEvent>()
        every { tickPublisher.publish(capture(captured)) } just Runs

        emitter.emit()

        verify(exactly = 1) { tickPublisher.publish(any()) }
        assert(captured.captured.source == "LAST_DAY_CLOSE")
        assert(captured.captured.stale)
        assert(captured.captured.price == BigDecimal("74000"))
    }

    @Test
    fun `Closed + cache 없음 + last-day-close 없음 — emit 없음`() {
        every { subscriptionManager.activeCodes() } returns setOf("005930")
        every { sessionResolver.resolve() } returns closedSession
        every { quoteCache.getLatest("005930") } returns null
        every { candleRepository.findLastClose("005930", any()) } returns null

        emitter.emit()

        verify(exactly = 0) { tickPublisher.publish(any()) }
    }

    @Test
    fun `activeCodes 비어 있으면 sessionResolver 호출조차 안 함`() {
        every { subscriptionManager.activeCodes() } returns emptySet()

        emitter.emit()

        verify(exactly = 0) { sessionResolver.resolve() }
        verify(exactly = 0) { tickPublisher.publish(any()) }
    }

    @Test
    fun `sessionResolver 예외 — 사이클 skip`() {
        every { subscriptionManager.activeCodes() } returns setOf("005930")
        every { sessionResolver.resolve() } throws RuntimeException("calendar down")

        emitter.emit()

        verify(exactly = 0) { tickPublisher.publish(any()) }
    }
}

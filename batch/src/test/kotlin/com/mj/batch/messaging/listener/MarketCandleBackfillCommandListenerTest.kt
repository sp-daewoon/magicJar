package com.mj.batch.messaging.listener

import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.event.MarketCandleBackfillCommandEvent
import com.mj.domain.market.port.KisRestPort
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * MarketCandleBackfillCommandListener 단위 테스트 — handoff 2026-05-04 D.
 */
class MarketCandleBackfillCommandListenerTest {
    private val kisRestPort = mockk<KisRestPort>()
    private val candleJpaRepository = mockk<CandleJpaRepository>(relaxed = true)
    private val listener = MarketCandleBackfillCommandListener(kisRestPort, candleJpaRepository)

    private val kst: ZoneId = ZoneId.of("Asia/Seoul")
    private val today: LocalDate = LocalDate.now(kst)

    private fun candle(
        code: String,
        date: LocalDate,
    ): Candle =
        Candle(
            code = code,
            interval = Interval.D1,
            openTime = date.atStartOfDay(kst).toInstant(),
            open = BigDecimal("100"),
            high = BigDecimal("110"),
            low = BigDecimal("95"),
            close = BigDecimal("105"),
            volume = 1000L,
            tradeCount = 50L,
        )

    private fun jpaCandle(
        code: String,
        openTime: Instant,
    ): CandleJpa =
        CandleJpa(
            code = code,
            interval = Interval.D1,
            openTime = openTime,
            open = BigDecimal("100"),
            high = BigDecimal("110"),
            low = BigDecimal("95"),
            close = BigDecimal("105"),
            volume = 1000L,
            tradeCount = 50L,
        )

    @Test
    fun `신규 종목 — 1년 전부터 today까지 fetch + saveAll`() {
        every { candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc("004090", Interval.D1) } returns null
        val fromSlot = slot<LocalDate>()
        val toSlot = slot<LocalDate>()
        every {
            kisRestPort.fetchCandles("004090", Interval.D1, capture(fromSlot), capture(toSlot))
        } returns listOf(candle("004090", today.minusDays(1)), candle("004090", today))

        listener.onCommand(MarketCandleBackfillCommandEvent(code = "004090"))

        assert(fromSlot.captured == today.minusYears(1)) { "from should be 1 year ago, got ${fromSlot.captured}" }
        assert(toSlot.captured == today) { "to should be today, got ${toSlot.captured}" }
        verify(exactly = 1) { candleJpaRepository.saveAll(any<Iterable<CandleJpa>>()) }
    }

    @Test
    fun `이미 어제까지 데이터 있으면 skip — fetch 미호출`() {
        every { candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc("005930", Interval.D1) } returns
            jpaCandle("005930", today.minusDays(1).atStartOfDay(kst).toInstant())

        listener.onCommand(MarketCandleBackfillCommandEvent(code = "005930"))

        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any(), any()) }
        verify(exactly = 0) { candleJpaRepository.saveAll(any<Iterable<CandleJpa>>()) }
    }

    @Test
    fun `기존 데이터 — 마지막 다음날부터 today까지 fetch (catch-up)`() {
        val lastInDb = today.minusDays(5)
        every { candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc("000660", Interval.D1) } returns
            jpaCandle("000660", lastInDb.atStartOfDay(kst).toInstant())

        val fromSlot = slot<LocalDate>()
        every {
            kisRestPort.fetchCandles("000660", Interval.D1, capture(fromSlot), any())
        } returns listOf(candle("000660", lastInDb.plusDays(1)))

        listener.onCommand(MarketCandleBackfillCommandEvent(code = "000660"))

        assert(fromSlot.captured == lastInDb.plusDays(1)) { "from should be lastInDb+1, got ${fromSlot.captured}" }
    }

    @Test
    fun `60s 내 같은 종목 재명령 — dedup으로 fetch skip`() {
        every { candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc("004090", Interval.D1) } returns null
        every {
            kisRestPort.fetchCandles("004090", Interval.D1, any(), any())
        } returns listOf(candle("004090", today))

        listener.onCommand(MarketCandleBackfillCommandEvent(code = "004090"))
        listener.onCommand(MarketCandleBackfillCommandEvent(code = "004090"))
        listener.onCommand(MarketCandleBackfillCommandEvent(code = "004090"))

        // 첫 호출만 실제 fetch + save
        verify(exactly = 1) { kisRestPort.fetchCandles("004090", Interval.D1, any(), any()) }
        assert(listener.isRecentlyFetched("004090"))
    }

    @Test
    fun `blank 코드 — skip, fetch 미호출`() {
        listener.onCommand(MarketCandleBackfillCommandEvent(code = ""))

        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any(), any()) }
    }

    @Test
    fun `미지원 interval (M1) — skip`() {
        listener.onCommand(MarketCandleBackfillCommandEvent(code = "005930", interval = "M1"))

        verify(exactly = 0) { kisRestPort.fetchCandles(any(), any(), any(), any()) }
    }

    @Test
    fun `KIS 응답 빈 리스트 — saveAll 미호출`() {
        every { candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc("999999", Interval.D1) } returns null
        every { kisRestPort.fetchCandles("999999", Interval.D1, any(), any()) } returns emptyList()

        listener.onCommand(MarketCandleBackfillCommandEvent(code = "999999"))

        verify(exactly = 1) { kisRestPort.fetchCandles("999999", Interval.D1, any(), any()) }
        verify(exactly = 0) { candleJpaRepository.saveAll(any<Iterable<CandleJpa>>()) }
    }

    @Test
    fun `KIS 예외 — swallowed, 짧은 retry TTL 후 재시도 가능`() {
        every { candleJpaRepository.findFirstByCodeAndIntervalOrderByOpenTimeDesc("004090", Interval.D1) } returns null
        every { kisRestPort.fetchCandles("004090", Interval.D1, any(), any()) } throws RuntimeException("KIS down")

        // 예외가 listener 밖으로 전파되지 않아야 한다
        listener.onCommand(MarketCandleBackfillCommandEvent(code = "004090"))

        verify(exactly = 1) { kisRestPort.fetchCandles("004090", Interval.D1, any(), any()) }
        // 예외 시 dedup TTL 짧게 적용 — short retry 가능
    }
}

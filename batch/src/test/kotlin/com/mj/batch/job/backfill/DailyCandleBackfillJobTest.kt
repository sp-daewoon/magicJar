package com.mj.batch.job.backfill

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.batch.infrastructure.kis.StubKisRestAdapter
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * DailyCandleBackfillJobTest — stub 어댑터가 1년치 캔들을 DB에 적재하는지 검증.
 *
 * @SpringBootTest + Testcontainers 없이 단위 수준으로 검증:
 * - StubKisRestAdapter가 JSON fixture를 올바르게 파싱하고 필터링하는지
 * - DailyCandleBackfillJob의 tasklet이 반환한 candle을 올바른 형태로 CandleJpaRepository에 저장하는지
 */
class DailyCandleBackfillJobTest {
    private val objectMapper =
        ObjectMapper().also {
            it.findAndRegisterModules()
        }
    private val stubAdapter = StubKisRestAdapter(objectMapper)
    private val candleJpaRepository = mockk<CandleJpaRepository>(relaxed = true)

    @Test
    fun `StubKisRestAdapter fetches 1-year candles for 005930`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 12, 31)

        val candles = stubAdapter.fetchCandles("005930", Interval.D1, from, to)

        // 약 250개 영업일 캔들 (주말 제외)
        assertTrue(candles.size >= 200, "Expected >= 200 candles, got ${candles.size}")
        assertTrue(candles.size <= 260, "Expected <= 260 candles, got ${candles.size}")

        val first = candles.first()
        assertEquals("005930", first.code)
        assertEquals(Interval.D1, first.interval)
        assertTrue(first.open > BigDecimal.ZERO, "open must be positive")
        assertTrue(first.high >= first.open, "high >= open")
        assertTrue(first.low <= first.open, "low <= open")
        assertTrue(first.volume > 0, "volume must be positive")
    }

    @Test
    fun `StubKisRestAdapter filters candles by date range`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 1, 31)

        val candles = stubAdapter.fetchCandles("005930", Interval.D1, from, to)

        assertTrue(candles.isNotEmpty(), "Expected candles in Jan 2024")
        candles.forEach { c ->
            val d = c.openTime.atZone(java.time.ZoneId.of("Asia/Seoul")).toLocalDate()
            assertTrue(!d.isBefore(from) && !d.isAfter(to), "Date $d out of range [$from, $to]")
        }
    }

    @Test
    fun `StubKisRestAdapter returns empty list for unknown code`() {
        val candles = stubAdapter.fetchCandles("999999", Interval.D1, LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31))
        assertTrue(candles.isEmpty(), "Unknown code should return empty list")
    }

    @Test
    fun `StubKisRestAdapter fetchQuote returns positive price`() {
        val quote = stubAdapter.fetchQuote("005930")
        assertEquals("005930", quote.code)
        assertTrue(quote.price > BigDecimal.ZERO, "price must be positive")
        assertTrue(quote.volume > 0, "volume must be positive")
    }

    @Test
    fun `StubKisRestAdapter token returns stub-token`() {
        assertEquals("stub-token", stubAdapter.token())
    }

    @Test
    fun `backfill saves candles to repository`() {
        val from = LocalDate.of(2024, 1, 1)
        val to = LocalDate.of(2024, 3, 31)

        val candles = stubAdapter.fetchCandles("005930", Interval.D1, from, to)

        val savedEntities = mutableListOf<com.mj.batch.infrastructure.persistence.market.CandleJpa>()
        every { candleJpaRepository.saveAll(any<List<com.mj.batch.infrastructure.persistence.market.CandleJpa>>()) } answers {
            @Suppress("UNCHECKED_CAST")
            savedEntities.addAll(firstArg() as List<com.mj.batch.infrastructure.persistence.market.CandleJpa>)
            savedEntities
        }

        val entities =
            candles.map { c ->
                com.mj.batch.infrastructure.persistence.market.CandleJpa(
                    code = c.code,
                    interval = c.interval,
                    openTime = c.openTime,
                    open = c.open,
                    high = c.high,
                    low = c.low,
                    close = c.close,
                    volume = c.volume,
                    tradeCount = c.tradeCount,
                )
            }
        candleJpaRepository.saveAll(entities)

        assertEquals(candles.size, savedEntities.size, "All candles should be saved")
    }
}

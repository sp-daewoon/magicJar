package com.mj.api.application.market

import com.mj.api.infrastructure.kis.KisChartCandleAdapter
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.UserCandleCachePort
import com.mj.domain.market.port.CandleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * UserChartCandleService — W/MO KIS direct fetch 라우팅 검증 (ADR 0050 R3-4).
 *
 * 시나리오:
 *   1. intervalRaw="W"  → kisChartCandleAdapter.fetchCandles(WEEK) 호출
 *   2. intervalRaw="WK" → kisChartCandleAdapter.fetchCandles(WEEK) 호출
 *   3. intervalRaw="MO" → kisChartCandleAdapter.fetchCandles(MONTH) 호출
 *   4. intervalRaw="MONTH" → kisChartCandleAdapter.fetchCandles(MONTH) 호출
 *   5. intervalRaw="Y"  → D1 합성 경로 (KIS adapter 미호출)
 *   6. limit 적용 — KIS 응답이 limit보다 많으면 takeLast(limit)
 *   7. KIS 키 미설정 — 빈 응답 그대로 반환
 */
class UserChartCandleServiceWmoTest {
    private val cache: UserCandleCachePort = mockk(relaxed = true)
    private val candleRepository: CandleRepository = mockk(relaxed = true)
    private val candleAggregator: CandleAggregator = CandleAggregator()
    private val kisChartCandleAdapter: KisChartCandleAdapter = mockk()

    private lateinit var service: UserChartCandleService

    @BeforeEach
    fun setUp() {
        service =
            UserChartCandleService(
                cache = cache,
                candleRepository = candleRepository,
                candleAggregator = candleAggregator,
                kisChartCandleAdapter = kisChartCandleAdapter,
            )

        // cache: always return empty (force DB fallback for D1/M1)
        every { cache.fetch(any(), any(), any(), any(), any()) } returns emptyList()
        every { candleRepository.find(any(), any(), any(), any()) } returns emptyList()
    }

    // -------------------------------------------------------------------------
    // 시나리오 1: "W" → WEEK KIS direct
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 1 - intervalRaw W routes to kisChartCandleAdapter WEEK`() {
        val intervalSlot = slot<Interval>()
        every {
            kisChartCandleAdapter.fetchCandles(
                code = "005930",
                interval = capture(intervalSlot),
                from = any(),
                to = any(),
            )
        } returns buildWeekCandles(3)

        val result = service.fetchByString("74683223", "005930", "W", limit = 200)

        assertEquals(Interval.WEEK, intervalSlot.captured)
        assertEquals(3, result.size)
    }

    // -------------------------------------------------------------------------
    // 시나리오 2: "WK" → WEEK KIS direct
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 2 - intervalRaw WK routes to kisChartCandleAdapter WEEK`() {
        val intervalSlot = slot<Interval>()
        every {
            kisChartCandleAdapter.fetchCandles(
                code = "005930",
                interval = capture(intervalSlot),
                from = any(),
                to = any(),
            )
        } returns buildWeekCandles(5)

        val result = service.fetchByString("74683223", "005930", "WK", limit = 200)

        assertEquals(Interval.WEEK, intervalSlot.captured)
        assertEquals(5, result.size)
    }

    // -------------------------------------------------------------------------
    // 시나리오 3: "MO" → MONTH KIS direct
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 3 - intervalRaw MO routes to kisChartCandleAdapter MONTH`() {
        val intervalSlot = slot<Interval>()
        every {
            kisChartCandleAdapter.fetchCandles(
                code = "005930",
                interval = capture(intervalSlot),
                from = any(),
                to = any(),
            )
        } returns buildMonthCandles(2)

        val result = service.fetchByString("74683223", "005930", "MO", limit = 60)

        assertEquals(Interval.MONTH, intervalSlot.captured)
        assertEquals(2, result.size)
    }

    // -------------------------------------------------------------------------
    // 시나리오 4: "MONTH" → MONTH KIS direct
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 4 - intervalRaw MONTH routes to kisChartCandleAdapter MONTH`() {
        val intervalSlot = slot<Interval>()
        every {
            kisChartCandleAdapter.fetchCandles(
                code = "005930",
                interval = capture(intervalSlot),
                from = any(),
                to = any(),
            )
        } returns buildMonthCandles(12)

        val result = service.fetchByString("74683223", "005930", "MONTH", limit = 60)

        assertEquals(Interval.MONTH, intervalSlot.captured)
        // limit=60, 12건 → 모두 반환
        assertEquals(12, result.size)
    }

    // -------------------------------------------------------------------------
    // 시나리오 5: "Y" → D1 합성 경로 (KIS adapter 미호출)
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 5 - intervalRaw Y does NOT call kisChartCandleAdapter`() {
        // D1 경로: cache empty → DB fallback → empty
        // Y는 합성 — D1 먼저 fetch 후 aggregateDailyToWeekly
        val result = service.fetchByString("74683223", "005930", "Y", limit = 20)

        // kisChartCandleAdapter는 호출되지 않아야 함
        verify(exactly = 0) { kisChartCandleAdapter.fetchCandles(any(), any(), any(), any()) }
        // DB도 비어있으므로 빈 응답
        assertEquals(0, result.size)
    }

    // -------------------------------------------------------------------------
    // 시나리오 6: limit 적용 — KIS 응답이 limit보다 많으면 takeLast(limit)
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 6 - result is trimmed to limit when KIS returns more candles`() {
        every {
            kisChartCandleAdapter.fetchCandles(
                code = "005930",
                interval = Interval.WEEK,
                from = any(),
                to = any(),
            )
        } returns buildWeekCandles(10) // 10봉 반환

        val result = service.fetchByString("74683223", "005930", "W", limit = 5)

        // limit=5이므로 최신 5봉만
        assertEquals(5, result.size)
        // takeLast → 최신 봉이 마지막에 위치 (openTime ASC 정렬 전제)
        assertTrue(result.first().openTime.isBefore(result.last().openTime))
    }

    // -------------------------------------------------------------------------
    // 시나리오 7: KIS 키 미설정 — 빈 응답 반환 (graceful degradation)
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 7 - empty result when KIS returns empty (key not set)`() {
        every {
            kisChartCandleAdapter.fetchCandles(
                code = "005930",
                interval = Interval.MONTH,
                from = any(),
                to = any(),
            )
        } returns emptyList()

        val result = service.fetchByString("74683223", "005930", "MO", limit = 60)

        assertEquals(0, result.size)
    }

    // -------------------------------------------------------------------------
    // 헬퍼 — 주봉/월봉 Candle 목록 생성
    // -------------------------------------------------------------------------

    /**
     * 주봉 Candle 목록 생성 — openTime ASC (2024-01-05, 2024-01-12, ...).
     */
    private fun buildWeekCandles(count: Int): List<Candle> =
        (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = Interval.WEEK,
                openTime =
                    LocalDate
                        .of(2024, 1, 5)
                        .plusWeeks(i.toLong())
                        .atStartOfDay(java.time.ZoneId.of("Asia/Seoul"))
                        .toInstant(),
                open = BigDecimal("73000"),
                high = BigDecimal("74000"),
                low = BigDecimal("72500"),
                close = BigDecimal("73500"),
                volume = 60_000_000L,
                tradeCount = 0L,
            )
        }

    /**
     * 월봉 Candle 목록 생성 — openTime ASC (2024-01-01, 2024-02-01, ...).
     */
    private fun buildMonthCandles(count: Int): List<Candle> =
        (0 until count).map { i ->
            val date = LocalDate.of(2024, 1, 1).plusMonths(i.toLong())
            Candle(
                code = "005930",
                interval = Interval.MONTH,
                openTime = date.atStartOfDay(java.time.ZoneId.of("Asia/Seoul")).toInstant(),
                open = BigDecimal("72000"),
                high = BigDecimal("75000"),
                low = BigDecimal("71000"),
                close = BigDecimal("73500"),
                volume = 250_000_000L,
                tradeCount = 0L,
            )
        }
}

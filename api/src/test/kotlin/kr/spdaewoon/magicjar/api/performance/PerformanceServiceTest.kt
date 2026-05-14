package kr.spdaewoon.magicjar.api.performance

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.controller.dto.performance.PerformanceSummaryDto
import kr.spdaewoon.magicjar.api.infrastructure.cache.RedisPerformanceCacheAdapter
import kr.spdaewoon.magicjar.api.service.performance.PerformanceCalculatorImpl
import kr.spdaewoon.magicjar.api.service.performance.PerformanceService
import kr.spdaewoon.magicjar.domain.performance.PnlDaily
import kr.spdaewoon.magicjar.domain.performance.port.PerformanceRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * PerformanceServiceTest — 5건 GREEN 의무 (ADR 0072 spec §3-2 정합).
 *
 * 1. cache HIT → 즉시 반환 (cacheHit=true)
 * 2. cache MISS → inline aggregate + Redis SET
 * 3. pnl_daily fallback (데이터 있는 경우)
 * 4. userId 격리 — 다른 userId 데이터 미노출
 * 5. today period (DAY) → inline aggregate
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
class PerformanceServiceTest {
    private val performanceRepository: PerformanceRepository = mock()
    private val performanceCalculator = PerformanceCalculatorImpl()
    private val cache: RedisPerformanceCacheAdapter = mock()
    private val objectMapper = ObjectMapper().apply { findAndRegisterModules() }

    private val sut = PerformanceService(performanceRepository, performanceCalculator, cache, objectMapper)

    private val userId = "user_test_001"
    private val kst = ZoneId.of("Asia/Seoul")

    private fun makePnlDaily(
        date: LocalDate,
        pnl: BigDecimal = BigDecimal("100000"),
    ) = PnlDaily(
        userId = userId,
        tradeDate = date,
        realizedPnl = pnl,
        winCount = 1,
        lossCount = 0,
        tradeCount = 1,
        avgProfit = pnl,
        avgLoss = null,
        equityAtClose = null,
    )

    @Test
    fun `cache HIT — 캐시 반환 cacheHit=true`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)
        val cachedDto =
            PerformanceSummaryDto(
                period = "MONTH",
                periodStart = from,
                periodEnd = to,
                totalPnl = BigDecimal("500000"),
                mddPct = BigDecimal("5.0000"),
                winRatePct = BigDecimal("70.00"),
                profitLossRatio = BigDecimal("2.0000"),
                tradeCount = 10,
                computedAt = OffsetDateTime.now(kst),
                cacheHit = false, // 캐시에서 읽을 때 false 저장 — service에서 true로 교체
            )
        val json = objectMapper.writeValueAsString(cachedDto)

        whenever(cache.getSummaryJson(eq(userId), eq("MONTH"), any(), any())).thenReturn(json)

        val result = sut.getSummary(userId, "MONTH", from, to)

        assertThat(result.cacheHit).isTrue()
        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("500000"))
    }

    @Test
    fun `cache MISS — inline aggregate 후 Redis SET 호출`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)

        whenever(cache.getSummaryJson(any(), any(), any(), any())).thenReturn(null)
        whenever(performanceRepository.findPnlDailyRange(eq(userId), eq(from), eq(to)))
            .thenReturn(listOf(makePnlDaily(LocalDate.of(2026, 5, 5))))

        val result = sut.getSummary(userId, "MONTH", from, to)

        assertThat(result.cacheHit).isFalse()
        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("100000"))
        verify(cache).putSummary(eq(userId), eq("MONTH"), eq(from), eq(to), any())
    }

    @Test
    fun `pnl_daily 데이터 있는 경우 — 실제 집계`() {
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)
        val pnlList =
            listOf(
                makePnlDaily(LocalDate.of(2026, 5, 5), BigDecimal("300000")),
                makePnlDaily(LocalDate.of(2026, 5, 8), BigDecimal("200000")),
            )

        whenever(cache.getSummaryJson(any(), any(), any(), any())).thenReturn(null)
        whenever(performanceRepository.findPnlDailyRange(eq(userId), any(), any())).thenReturn(pnlList)

        val result = sut.getSummary(userId, "MONTH", from, to)

        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("500000"))
        assertThat(result.tradeCount).isEqualTo(2)
        assertThat(result.winRatePct).isEqualByComparingTo(BigDecimal("100.00"))
    }

    @Test
    fun `userId 격리 — 다른 userId 데이터 미노출`() {
        val userId2 = "user_test_002"
        val from = LocalDate.of(2026, 5, 1)
        val to = LocalDate.of(2026, 5, 14)

        whenever(cache.getSummaryJson(eq(userId), any(), any(), any())).thenReturn(null)
        whenever(cache.getSummaryJson(eq(userId2), any(), any(), any())).thenReturn(null)
        whenever(
            performanceRepository.findPnlDailyRange(eq(userId), any(), any()),
        ).thenReturn(listOf(makePnlDaily(LocalDate.of(2026, 5, 5))))
        whenever(performanceRepository.findPnlDailyRange(eq(userId2), any(), any())).thenReturn(emptyList())

        val resultUser1 = sut.getSummary(userId, "MONTH", from, to)
        val resultUser2 = sut.getSummary(userId2, "MONTH", from, to)

        assertThat(resultUser1.totalPnl).isGreaterThan(BigDecimal.ZERO)
        assertThat(resultUser2.totalPnl).isEqualByComparingTo(BigDecimal.ZERO)
    }

    @Test
    fun `DAY period — today inline aggregate`() {
        val today = LocalDate.now(kst)

        whenever(cache.getSummaryJson(any(), any(), any(), any())).thenReturn(null)
        whenever(performanceRepository.findPnlDailyRange(eq(userId), eq(today), eq(today)))
            .thenReturn(listOf(makePnlDaily(today, BigDecimal("80000"))))

        val result = sut.getSummary(userId, "DAY", null, null)

        assertThat(result.period).isEqualTo("DAY")
        assertThat(result.periodStart).isEqualTo(today)
        assertThat(result.totalPnl).isEqualByComparingTo(BigDecimal("80000"))
    }
}

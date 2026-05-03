package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta
import com.mj.domain.market.cache.port.CacheMetaRepository
import com.mj.domain.market.port.CandleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * CandleCacheService 단위 테스트 (T19-T3 정정).
 *
 * 정책 변경 — D1 fallback 정책 폐기 (사용자 보고로 회귀 fix).
 * 사용자가 분봉 선택했는데 일봉이 응답되면 혼란 → 분봉은 있는 그대로 반환,
 * 0건이면 빈 배열 반환 + 백그라운드 fetch trigger.
 *
 * - cache hit: DB 분봉 있고 meta 있으면 즉시 반환 + last_accessed_at 갱신
 * - cache miss: DB 분봉 0건이면 빈 배열 반환 + background fetch trigger
 * - cache partial (meta 부재): legacy 시드 — 있는 그대로 반환, trigger 안 함
 * - D1 요청: cache 로직 우회, candleRepository 직접 조회
 */
class CandleCacheServiceTest {
    private val candleRepository: CandleRepository = mockk(relaxed = true)
    private val cacheMetaRepository: CacheMetaRepository = mockk(relaxed = true)
    private val candleQueryService: CandleQueryService = mockk(relaxed = true)

    private val sut =
        CandleCacheService(
            candleRepository = candleRepository,
            cacheMetaRepository = cacheMetaRepository,
            candleQueryService = candleQueryService,
            batchBaseUrl = "http://localhost:8081",
        )

    // --------------------------------------------------
    // 픽스처
    // --------------------------------------------------

    private fun candleFixture(interval: Interval = Interval.M30) =
        Candle(
            code = "005930",
            interval = interval,
            openTime = Instant.parse("2026-04-30T09:30:00Z"),
            open = BigDecimal("60000"),
            high = BigDecimal("60500"),
            low = BigDecimal("59500"),
            close = BigDecimal("60200"),
            volume = 1_000_000L,
            tradeCount = 5_000L,
        )

    private fun cacheMetaFixture() =
        CacheMeta(
            code = "005930",
            interval = Interval.M30,
            lastAccessedAt = Instant.parse("2026-04-30T10:00:00Z"),
            oldestBar = Instant.parse("2023-01-01T00:00:00Z"),
            newestBar = Instant.parse("2026-04-30T15:30:00Z"),
            byteEstimate = 1_000_000L,
        )

    // --------------------------------------------------
    // 테스트
    // --------------------------------------------------

    @Test
    fun `D1 요청은 cache 로직 우회 — candleRepository 직접 조회`() {
        val from = Instant.parse("2026-01-01T00:00:00Z")
        val to = Instant.parse("2026-04-30T23:59:59Z")
        val expected = listOf(candleFixture(Interval.D1))

        every { candleRepository.find("005930", Interval.D1, from, to) } returns expected

        val result = sut.fetchOrTrigger("005930", Interval.D1, from, to)

        assertThat(result).isEqualTo(expected)
        // cache meta 조회 없음
        verify(exactly = 0) { cacheMetaRepository.findByCodeAndInterval(any(), any()) }
    }

    @Test
    fun `M30 cache hit — 분봉 있으면 즉시 반환하고 last_accessed_at 갱신`() {
        val to = Instant.now()
        val from = to.minusSeconds(86400 * 30)
        // 충분한 캔들 제공 (50개 = 영업일 수 30 × 0.7 = 21개 이상)
        val candles =
            (1..50).map { i ->
                candleFixture(Interval.M30).copy(openTime = from.plusSeconds(i * 1800L))
            }

        every { candleRepository.find("005930", Interval.M30, any(), any()) } returns candles
        every { cacheMetaRepository.findByCodeAndInterval("005930", Interval.M30) } returns cacheMetaFixture()
        every { cacheMetaRepository.save(any()) } answers { firstArg() }

        val result = sut.fetchOrTrigger("005930", Interval.M30, from, to)

        assertThat(result).hasSize(50)
        // last_accessed_at 갱신 확인
        verify(exactly = 1) { cacheMetaRepository.save(any()) }
    }

    @Test
    fun `M30 cache miss — 0건이면 빈 배열 반환 (D1 fallback 폐기)`() {
        val to = Instant.now()
        val from = to.minusSeconds(86400L)

        every { candleRepository.find("005930", Interval.M30, any(), any()) } returns emptyList()
        every { cacheMetaRepository.findByCodeAndInterval("005930", Interval.M30) } returns null

        val result = sut.fetchOrTrigger("005930", Interval.M30, from, to)

        // 빈 배열 — 사용자가 M30 선택했는데 D1을 주면 혼란.
        assertThat(result).isEmpty()
        // D1 조회는 발생하지 않아야 함 (fallback 정책 폐기 확인)
        verify(exactly = 0) { candleRepository.find("005930", Interval.D1, any(), any()) }
    }

    @Test
    fun `M30 partial — candles 있고 meta 없으면 legacy 시드로 그대로 반환`() {
        val to = Instant.now()
        val from = to.minusSeconds(86400 * 7)
        val candles =
            (1..10).map { i ->
                candleFixture(Interval.M30).copy(openTime = from.plusSeconds(i * 1800L))
            }

        every { candleRepository.find("005930", Interval.M30, any(), any()) } returns candles
        every { cacheMetaRepository.findByCodeAndInterval("005930", Interval.M30) } returns null

        val result = sut.fetchOrTrigger("005930", Interval.M30, from, to)

        // legacy 시드는 그대로 반환 (백그라운드 trigger 없음)
        assertThat(result).hasSize(10)
    }
}

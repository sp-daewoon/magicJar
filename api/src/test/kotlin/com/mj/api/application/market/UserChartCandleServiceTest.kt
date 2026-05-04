package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.UserCandleCachePort
import com.mj.domain.market.port.CandleRepository
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * UserChartCandleService 단위 테스트 — ADR 0050 R3-1.
 *
 * 검증:
 *   1. cache hit → 즉시 응답 + touchRecent (DB 안 거침)
 *   2. cache miss → DB 조회 + Redis warm-up + 응답
 *   3. cache miss + DB miss → 빈 응답 + touchRecent만 (warm-up 안 함)
 */
class UserChartCandleServiceTest {
    private val cache = mockk<UserCandleCachePort>()
    private val candleRepository = mockk<CandleRepository>()
    private val sut = UserChartCandleService(cache, candleRepository)

    private val userId = UserChartCandleService.DEFAULT_USER_ID
    private val code = "005930"

    private fun candleFixture(openTime: Instant = Instant.parse("2026-04-30T00:00:00Z")) =
        Candle(
            code = code,
            interval = Interval.M1,
            openTime = openTime,
            open = BigDecimal("60000"),
            high = BigDecimal("60500"),
            low = BigDecimal("59500"),
            close = BigDecimal("60200"),
            volume = 1_000_000L,
            tradeCount = 5_000L,
        )

    @Test
    fun `cache hit — DB 안 거치고 즉시 반환 + touchRecent`() {
        val cached = (1..200).map { candleFixture(Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L)) }
        every { cache.fetch(userId, code, "M1", null, 200) } returns cached
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetch(userId, code, Interval.M1, limit = 200)

        assert(result.size == 200)
        verify(exactly = 1) { cache.fetch(userId, code, "M1", null, 200) }
        verify(exactly = 1) { cache.touchRecent(userId, code) }
        // DB 호출 없음 — cache hit 검증
        verify(exactly = 0) { candleRepository.find(any(), any(), any(), any()) }
    }

    @Test
    fun `cache miss — DB 조회 + Redis warm-up`() {
        val dbCandles = (1..150).map { candleFixture(Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L)) }
        every { cache.fetch(userId, code, "M1", null, 200) } returnsMany listOf(emptyList(), dbCandles)
        every { candleRepository.find(code, Interval.M1, any(), any()) } returns dbCandles
        every { cache.upsertAll(userId, code, "M1", dbCandles) } just Runs
        every { cache.setLastFetch(userId, code, "M1", any()) } just Runs
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetch(userId, code, Interval.M1, limit = 200)

        verify(exactly = 1) { candleRepository.find(code, Interval.M1, any(), any()) }
        verify(exactly = 1) { cache.upsertAll(userId, code, "M1", dbCandles) }
        verify(exactly = 1) { cache.setLastFetch(userId, code, "M1", any()) }
        verify(exactly = 1) { cache.touchRecent(userId, code) }
        assert(result.size == 150)
    }

    @Test
    fun `cache miss + DB miss — 빈 응답 + touchRecent만`() {
        every { cache.fetch(userId, code, "M1", null, 200) } returns emptyList()
        every { candleRepository.find(code, Interval.M1, any(), any()) } returns emptyList()
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetch(userId, code, Interval.M1, limit = 200)

        assert(result.isEmpty())
        verify(exactly = 1) { candleRepository.find(code, Interval.M1, any(), any()) }
        verify(exactly = 0) { cache.upsertAll(any(), any(), any(), any()) }
        verify(exactly = 0) { cache.setLastFetch(any(), any(), any(), any()) }
        verify(exactly = 1) { cache.touchRecent(userId, code) }
    }

    @Test
    fun `LRU 11번째 진입 — touchRecent에서 evict 1개 발생`() {
        val cached = (1..200).map { candleFixture() }
        every { cache.fetch(userId, code, "M1", null, 200) } returns cached
        every { cache.touchRecent(userId, code) } returns listOf("OLD_CODE")

        sut.fetch(userId, code, Interval.M1, limit = 200)

        verify(exactly = 1) { cache.touchRecent(userId, code) }
        // evict log 확인은 service 내부 (assertion으로 직접 검증 어려움 — log only)
    }

    @Test
    fun `before cursor 지정 — infinite scroll 흐름`() {
        val before = Instant.parse("2026-04-30T00:00:00Z")
        val cached = (1..200).map { candleFixture(before.minusSeconds(it * 60L)) }
        every { cache.fetch(userId, code, "M1", before, 200) } returns cached
        every { cache.touchRecent(userId, code) } returns emptyList()

        // before 지정 시 cache hit 조건 변경 — limit 만족하면 cache hit
        val result = sut.fetch(userId, code, Interval.M1, limit = 200, before = before)

        assert(result.size == 200)
        verify(exactly = 0) { candleRepository.find(any(), any(), any(), any()) }
    }
}

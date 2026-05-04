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
    private val candleAggregator = CandleAggregator() // 실제 로직 사용 (단위 테스트)
    private val sut = UserChartCandleService(cache, candleRepository, candleAggregator)

    private val userId = UserChartCandleService.DEFAULT_USER_ID
    private val code = "005930"

    private fun candleFixture(
        openTime: Instant = Instant.parse("2026-04-30T00:00:00Z"),
        interval: Interval = Interval.M1,
    ) = Candle(
        code = code,
        interval = interval,
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

    // ── R3-3 합성 interval 테스트 ──────────────────────────────────────────────

    @Test
    fun `fetchByString D1 alias — 기존 fetch와 동일`() {
        val cached = (1..200).map { candleFixture(Instant.parse("2026-01-01T00:00:00Z").plusSeconds(it * 86400L), Interval.D1) }
        every { cache.fetch(userId, code, "D1", null, 200) } returns cached
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "DAY", limit = 200)

        assert(result.size == 200)
        verify(exactly = 1) { cache.fetch(userId, code, "D1", null, 200) }
    }

    @Test
    fun `fetchByString M5 — M1 base 캐시에서 조회 후 5분봉 합성`() {
        // KST 09:00 시작 30개 M1 → 5분봉 6개로 합성될 것
        val baseM1 =
            (0 until 30).map {
                candleFixture(
                    Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L), // KST 09:00 = UTC 00:00
                    Interval.M1,
                )
            }
        // M5 limit=200 → m1Limit = 200*5 = 1000
        every { cache.fetch(userId, code, "M1", null, 1000) } returns baseM1
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "M5", limit = 200)

        // 30개 M1 → 6개 M5
        assert(result.size == 6) { "expected 6 M5 candles, got ${result.size}" }
        // 첫 봉의 openTime이 base의 첫 봉과 같아야
        assert(result.first().openTime == baseM1.first().openTime)
        verify(exactly = 1) { cache.fetch(userId, code, "M1", null, 1000) }
    }

    @Test
    fun `fetchByString H1 — M1 base + 60분 합성`() {
        // 60개 M1 → 1개 H1
        val baseM1 =
            (0 until 60).map {
                candleFixture(
                    Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L),
                    Interval.M1,
                )
            }
        // limit*60=12000 > MAX_BASE_LIMIT(10_000)이라 clamp
        every { cache.fetch(userId, code, "M1", null, 10_000) } returns baseM1
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "H1", limit = 200)

        assert(result.size == 1) { "expected 1 H1 candle from 60 M1, got ${result.size}" }
    }

    @Test
    fun `fetchByString W — D1 base + 주봉 합성`() {
        // 14일 D1 → 3주 (월~금 기준 ISO week)
        val baseD1 =
            (0 until 14).map {
                candleFixture(
                    Instant.parse("2026-04-13T00:00:00Z").plusSeconds(it * 86400L),
                    Interval.D1,
                )
            }
        // W → baseLimit = limit * 5 = 1000
        every { cache.fetch(userId, code, "D1", null, 1000) } returns baseD1
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "W", limit = 200)

        // 14일이 ISO week로 3주에 걸침 (대략)
        assert(result.size in 2..4) { "expected 2~4 W candles from 14 D1, got ${result.size}" }
    }

    @Test
    fun `fetchByString MO — D1 base + 월봉 합성`() {
        // 4월 5일 + 5월 5일 → 2개월
        val baseD1 =
            listOf(
                candleFixture(Instant.parse("2026-04-05T00:00:00Z"), Interval.D1),
                candleFixture(Instant.parse("2026-04-15T00:00:00Z"), Interval.D1),
                candleFixture(Instant.parse("2026-05-05T00:00:00Z"), Interval.D1),
                candleFixture(Instant.parse("2026-05-15T00:00:00Z"), Interval.D1),
            )
        // MO → baseLimit = 200 * 22 = 4400
        every { cache.fetch(userId, code, "D1", null, 4400) } returns baseD1
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "MO", limit = 200)

        assert(result.size == 2) { "expected 2 MO candles, got ${result.size}" }
    }

    @Test
    fun `fetchByString — 잘못된 interval은 IllegalArgumentException`() {
        try {
            sut.fetchByString(userId, code, "ZZZ", limit = 200)
            assert(false) { "expected IllegalArgumentException" }
        } catch (_: IllegalArgumentException) {
            // expected
        }
    }
}

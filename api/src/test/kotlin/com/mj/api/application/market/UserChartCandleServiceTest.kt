package com.mj.api.application.market

import com.mj.api.infrastructure.kis.KisChartCandleAdapter
import com.mj.api.messaging.producer.UserCandleEventPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.UserCandleCachePort
import io.mockk.every
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
 *
 * W/MO KIS direct fetch 검증은 [UserChartCandleServiceWmoTest] 참조.
 * R3-4: UserCandleEventPublisher 추가 — relaxed mock으로 publish 호출 허용.
 */
class UserChartCandleServiceTest {
    private val cache = mockk<UserCandleCachePort>()
    private val candleAggregator = CandleAggregator() // 실제 로직 사용 (단위 테스트)
    private val kisChartCandleAdapter = mockk<KisChartCandleAdapter>(relaxed = true)
    private val userCandleEventPublisher = mockk<UserCandleEventPublisher>(relaxed = true) // R3-4 fail-soft
    private val candleCacheService = mockk<CandleCacheService>(relaxed = true) // R3-5b — backfill trigger 호출 허용
    private val sut =
        UserChartCandleService(
            cache,
            candleAggregator,
            kisChartCandleAdapter,
            userCandleEventPublisher,
            candleCacheService,
        )

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
    fun `cache hit — 즉시 반환 + touchRecent`() {
        val cached = (1..200).map { candleFixture(Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L)) }
        every { cache.fetch(userId, code, "M1", null, 200) } returns cached
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetch(userId, code, Interval.M1, limit = 200)

        assert(result.size == 200)
        verify(exactly = 1) { cache.fetch(userId, code, "M1", null, 200) }
        verify(exactly = 1) { cache.touchRecent(userId, code) }
    }

    @Test
    fun `R3-5c — M1 cache miss 시 yfinance backfill trigger + 빈 응답 (DB 호출 0)`() {
        every { cache.fetch(userId, code, "M1", null, 200) } returns emptyList()
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetch(userId, code, Interval.M1, limit = 200)

        assert(result.isEmpty()) { "cache miss → 빈 응답 (DB 안 거침)" }
        verify(exactly = 1) { cache.touchRecent(userId, code) }
        verify(exactly = 1) { candleCacheService.triggerBackgroundFetchAsync(code, null) }
        verify(exactly = 0) { cache.upsertAll(any(), any(), any(), any()) }
    }

    @Test
    fun `R3-5c — D1 cache miss 시 빈 응답 (trigger 안 함, KIS direct는 fetchByString 분기에서 처리)`() {
        every { cache.fetch(userId, code, "D1", null, 200) } returns emptyList()
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetch(userId, code, Interval.D1, limit = 200)

        assert(result.isEmpty())
        verify(exactly = 1) { cache.touchRecent(userId, code) }
        // D1은 M1 trigger와 무관 — fetchByString의 D1 alias가 fetchKisDirect로 라우팅
        verify(exactly = 0) { candleCacheService.triggerBackgroundFetchAsync(any(), any()) }
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
    }

    // ── R3-3 합성 interval 테스트 ──────────────────────────────────────────────

    @Test
    fun `fetchByString D1 alias — R3-5a KIS direct fetch (D1 interval)`() {
        val limit = 200
        val dayCandles =
            (0 until limit).map {
                candleFixture(
                    Instant.parse("2025-01-01T00:00:00Z").plusSeconds(it * 86400L),
                    Interval.D1,
                )
            }
        every {
            kisChartCandleAdapter.fetchCandles(
                code = code,
                interval = Interval.D1,
                from = any(),
                to = any(),
            )
        } returns dayCandles
        // R3-5c (2026-05-05): fetchKisDirect도 cache 우선 — cache miss 시 KIS direct.
        every { cache.fetch(userId, code, "D1", null, limit) } returns emptyList()
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "DAY", limit = limit)

        // R3-5a: D1도 KIS direct path 사용 (DB fallback 제거)
        assert(result.size == limit) { "expected $limit D1 candles from KIS direct, got ${result.size}" }
        verify(exactly = 1) {
            kisChartCandleAdapter.fetchCandles(code = code, interval = Interval.D1, from = any(), to = any())
        }
        // cache.fetch는 D1 path에서 한 번 호출 (cache 검사 후 miss → KIS direct)
        verify(exactly = 1) { cache.fetch(userId, code, "D1", null, limit) }
    }

    @Test
    fun `fetchByString M5 — M1 base 캐시에서 조회 후 5분봉 합성`() {
        val limit = 6
        val targetMinutes = 5
        val m1Limit = limit * targetMinutes // 30 — cache hit 조건 충족
        val baseM1 =
            (0 until m1Limit).map {
                candleFixture(
                    Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L),
                    Interval.M1,
                )
            }
        every { cache.fetch(userId, code, "M1", null, m1Limit) } returns baseM1
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "M5", limit = limit)

        assert(result.size == limit) { "expected $limit M5 candles, got ${result.size}" }
        assert(result.first().openTime == baseM1.first().openTime)
        verify(exactly = 1) { cache.fetch(userId, code, "M1", null, m1Limit) }
    }

    @Test
    fun `fetchByString H1 — M1 base + 60분 합성`() {
        val limit = 1
        val targetMinutes = 60
        val m1Limit = limit * targetMinutes // 60
        val baseM1 =
            (0 until m1Limit).map {
                candleFixture(
                    Instant.parse("2026-04-30T00:00:00Z").plusSeconds(it * 60L),
                    Interval.M1,
                )
            }
        every { cache.fetch(userId, code, "M1", null, m1Limit) } returns baseM1
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "H1", limit = limit)

        assert(result.size == 1) { "expected 1 H1 candle from 60 M1, got ${result.size}" }
    }

    @Test
    fun `fetchByString W — R3-4 KIS direct fetch (WEEK interval)`() {
        val limit = 4
        val weekCandles =
            (0 until limit).map {
                candleFixture(
                    Instant.parse("2024-01-05T00:00:00Z").plusSeconds(it * 7L * 86400L),
                    Interval.WEEK,
                )
            }
        every {
            kisChartCandleAdapter.fetchCandles(
                code = code,
                interval = Interval.WEEK,
                from = any(),
                to = any(),
            )
        } returns weekCandles
        every { cache.fetch(userId, code, "WEEK", null, limit) } returns emptyList()
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "W", limit = limit)

        // R3-4: KIS direct — 반환된 봉 수 그대로
        assert(result.size == limit) { "expected $limit W candles from KIS direct, got ${result.size}" }
        verify(exactly = 1) {
            kisChartCandleAdapter.fetchCandles(code = code, interval = Interval.WEEK, from = any(), to = any())
        }
    }

    @Test
    fun `fetchByString MO — R3-4 KIS direct fetch (MONTH interval)`() {
        val limit = 2
        val monthCandles =
            (0 until limit).map {
                candleFixture(
                    Instant.parse("2024-01-01T00:00:00Z").plusSeconds(it * 30L * 86400L),
                    Interval.MONTH,
                )
            }
        every {
            kisChartCandleAdapter.fetchCandles(
                code = code,
                interval = Interval.MONTH,
                from = any(),
                to = any(),
            )
        } returns monthCandles
        every { cache.fetch(userId, code, "MONTH", null, limit) } returns emptyList()
        every { cache.touchRecent(userId, code) } returns emptyList()

        val result = sut.fetchByString(userId, code, "MO", limit = limit)

        assert(result.size == limit) { "expected $limit MO candles from KIS direct, got ${result.size}" }
        verify(exactly = 1) {
            kisChartCandleAdapter.fetchCandles(code = code, interval = Interval.MONTH, from = any(), to = any())
        }
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

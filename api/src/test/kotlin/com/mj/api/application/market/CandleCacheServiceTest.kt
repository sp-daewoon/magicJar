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
 * CandleCacheService 단위 테스트 — v2 정책 (ADR 0048).
 *
 * v2 변경:
 *   - 캐시 단위는 M1·D1 only.
 *   - M5/M15/M30/H1 요청은 CandleQueryService.queryByString → CandleAggregator 경유 (이 서비스 불경유).
 *   - fetchOrTrigger는 D1·M1만 받음. M1 miss 시 M1 background fetch trigger.
 */
class CandleCacheServiceTest {
    private val candleRepository: CandleRepository = mockk(relaxed = true)
    private val cacheMetaRepository: CacheMetaRepository = mockk(relaxed = true)
    private val instrumentService: com.mj.api.application.instrument.InstrumentService = mockk(relaxed = true)
    private val restClientBuilder: org.springframework.web.client.RestClient.Builder = mockk(relaxed = true)

    private val sut =
        CandleCacheService(
            candleRepository = candleRepository,
            cacheMetaRepository = cacheMetaRepository,
            instrumentService = instrumentService,
            restClientBuilder = restClientBuilder,
            pyworkerBaseUrl = "http://localhost:8000",
        )

    // --------------------------------------------------
    // 픽스처
    // --------------------------------------------------

    private fun candleFixture(interval: Interval = Interval.M1) =
        Candle(
            code = "005930",
            interval = interval,
            openTime = Instant.parse("2026-04-30T09:01:00Z"),
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
            interval = Interval.M1,
            lastAccessedAt = Instant.parse("2026-04-30T10:00:00Z"),
            oldestBar = Instant.parse("2026-04-24T00:00:00Z"),
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
    fun `M1 cache hit — M1 분봉 있으면 즉시 반환하고 last_accessed_at 갱신`() {
        val to = Instant.now()
        val from = to.minusSeconds(86400 * 7)
        val candles =
            (1..50).map { i ->
                candleFixture(Interval.M1).copy(openTime = from.plusSeconds(i * 60L))
            }

        every { candleRepository.find("005930", Interval.M1, any(), any()) } returns candles
        every { cacheMetaRepository.findByCodeAndInterval("005930", Interval.M1) } returns cacheMetaFixture()
        every { cacheMetaRepository.save(any()) } answers { firstArg() }

        val result = sut.fetchOrTrigger("005930", Interval.M1, from, to)

        assertThat(result).hasSize(50)
        // last_accessed_at 갱신 확인
        verify(exactly = 1) { cacheMetaRepository.save(any()) }
    }

    @Test
    fun `M1 cache miss — 0건이면 빈 배열 반환`() {
        val to = Instant.now()
        val from = to.minusSeconds(86400L)

        every { candleRepository.find("005930", Interval.M1, any(), any()) } returns emptyList()
        every { cacheMetaRepository.findByCodeAndInterval("005930", Interval.M1) } returns null

        val result = sut.fetchOrTrigger("005930", Interval.M1, from, to)

        assertThat(result).isEmpty()
        // D1 조회는 발생하지 않아야 함 (fallback 정책 폐기 확인)
        verify(exactly = 0) { candleRepository.find("005930", Interval.D1, any(), any()) }
    }

    @Test
    fun `M1 partial — candles 있고 meta 없으면 legacy 시드로 그대로 반환`() {
        val to = Instant.now()
        val from = to.minusSeconds(86400 * 7)
        val candles =
            (1..10).map { i ->
                candleFixture(Interval.M1).copy(openTime = from.plusSeconds(i * 60L))
            }

        every { candleRepository.find("005930", Interval.M1, any(), any()) } returns candles
        every { cacheMetaRepository.findByCodeAndInterval("005930", Interval.M1) } returns null

        val result = sut.fetchOrTrigger("005930", Interval.M1, from, to)

        // legacy 시드는 그대로 반환 (백그라운드 trigger 없음)
        assertThat(result).hasSize(10)
    }

    @Test
    fun `triggerBackgroundFetchAsync — pyworker 연결 실패 시 예외 전파 없이 warn 로그만`() {
        // pyworker URL이 없는 호스트 → RestClient 연결 실패 예상
        // @Async는 테스트 컨텍스트에서 동기 실행 (Spring 컨텍스트 없으므로)
        // → 예외가 외부로 전파되지 않고 내부 catch에서 warn으로 처리되는지 검증
        val sutWithBadUrl =
            CandleCacheService(
                candleRepository = candleRepository,
                cacheMetaRepository = cacheMetaRepository,
                instrumentService = instrumentService,
                restClientBuilder =
                    org.springframework.web.client.RestClient
                        .builder(),
                pyworkerBaseUrl = "http://127.0.0.1:19999", // 없는 포트
            )

        // 예외가 밖으로 나오지 않아야 함 (catch 후 warn만)
        org.junit.jupiter.api.assertDoesNotThrow {
            sutWithBadUrl.triggerBackgroundFetchAsync("004090")
        }
    }
}

package com.mj.api.application.market

import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta
import com.mj.domain.market.cache.port.CacheMetaRepository
import com.mj.domain.market.port.CandleRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * CacheEvictionService 단위 테스트 (T19-T7, v2 정책 정합).
 *
 * v2 변경: 캐시 단위는 M1·D1 only. CacheMeta.interval은 M1 또는 D1.
 *
 * - threshold 미만 → eviction 0 row
 * - threshold 초과 → ASC 순서대로 가장 오래된 cache 항목 삭제
 * - 한도 도달까지만 삭제 (overshoot 방지)
 * - D1 interval은 eviction 대상 제외
 */
class CacheEvictionServiceTest {
    private val candleRepository: CandleRepository = mockk(relaxed = true)
    private val cacheMetaRepository: CacheMetaRepository = mockk(relaxed = true)

    // 기본 max-bytes = 1000 bytes (작은 값으로 테스트 편의)
    private val maxBytes = 1000L

    private val sut =
        CacheEvictionService(
            candleRepository = candleRepository,
            cacheMetaRepository = cacheMetaRepository,
            maxBytes = maxBytes,
        )

    // --------------------------------------------------
    // 픽스처
    // --------------------------------------------------

    private fun metaFixture(
        code: String,
        interval: Interval = Interval.M1,
        lastAccessedAt: Instant = Instant.parse("2026-01-01T00:00:00Z"),
        byteEstimate: Long = 200L,
    ) = CacheMeta(
        code = code,
        interval = interval,
        lastAccessedAt = lastAccessedAt,
        oldestBar = Instant.parse("2026-04-24T00:00:00Z"),
        newestBar = Instant.parse("2026-04-30T15:30:00Z"),
        byteEstimate = byteEstimate,
    )

    // --------------------------------------------------
    // 테스트
    // --------------------------------------------------

    @Test
    fun `threshold 미만 — eviction 0건`() {
        // total = 500 bytes < maxBytes(1000)
        val allMeta =
            listOf(
                metaFixture("A", byteEstimate = 200L),
                metaFixture("B", byteEstimate = 300L),
            )
        every { cacheMetaRepository.listByLastAccessedAsc(Int.MAX_VALUE) } returns allMeta

        val result = sut.evictIfNeeded()

        assertThat(result.evictedCount).isEqualTo(0)
        assertThat(result.reclaimedBytes).isEqualTo(0L)
        verify(exactly = 0) { candleRepository.deleteByCodeAndInterval(any(), any()) }
        verify(exactly = 0) { cacheMetaRepository.deleteByCodeAndInterval(any(), any()) }
    }

    @Test
    fun `threshold 초과 — 가장 오래된 항목부터 순서대로 삭제`() {
        // total = 200 + 300 + 600 + 500 = 1600 bytes > maxBytes(1000)
        // excess = 600, target = 600 * 1.1 = 660 bytes
        // A(200) + B(300) = 500 < 660 → C(600) 삭제 후 1100 >= 660 → 종료 (D 미삭제)
        val oldestMeta = metaFixture("A", lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z"), byteEstimate = 200L)
        val middleMeta = metaFixture("B", lastAccessedAt = Instant.parse("2026-02-01T00:00:00Z"), byteEstimate = 300L)
        val thirdMeta = metaFixture("C", lastAccessedAt = Instant.parse("2026-03-01T00:00:00Z"), byteEstimate = 600L)
        val newestMeta = metaFixture("D", lastAccessedAt = Instant.parse("2026-04-01T00:00:00Z"), byteEstimate = 500L)
        val allMeta = listOf(oldestMeta, middleMeta, thirdMeta, newestMeta) // ASC 정렬 상태

        every { cacheMetaRepository.listByLastAccessedAsc(Int.MAX_VALUE) } returns allMeta
        every { candleRepository.deleteByCodeAndInterval(any(), any()) } returns 10

        val result = sut.evictIfNeeded()

        // A(200) + B(300) + C(600) = 1100 >= 660 → 종료 (D 미삭제)
        assertThat(result.evictedCount).isEqualTo(3)
        assertThat(result.reclaimedBytes).isEqualTo(1100L)

        // 삭제 순서 검증: A → B → C (D는 삭제 안 함)
        verify(exactly = 1) { candleRepository.deleteByCodeAndInterval("A", Interval.M1) }
        verify(exactly = 1) { candleRepository.deleteByCodeAndInterval("B", Interval.M1) }
        verify(exactly = 1) { candleRepository.deleteByCodeAndInterval("C", Interval.M1) }
        verify(exactly = 0) { candleRepository.deleteByCodeAndInterval("D", Interval.M1) }
        verify(exactly = 1) { cacheMetaRepository.deleteByCodeAndInterval("A", Interval.M1) }
        verify(exactly = 1) { cacheMetaRepository.deleteByCodeAndInterval("B", Interval.M1) }
        verify(exactly = 1) { cacheMetaRepository.deleteByCodeAndInterval("C", Interval.M1) }
        verify(exactly = 0) { cacheMetaRepository.deleteByCodeAndInterval("D", Interval.M1) }
    }

    @Test
    fun `한도 도달 시 overshoot 없이 종료`() {
        // total = 1200 bytes > maxBytes(1000)
        // excess = 200, target = 220 bytes
        // A(300) 하나 삭제 후 reclaimedBytes(300) >= 220 → B는 삭제하지 않음
        val allMeta =
            listOf(
                metaFixture("A", lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z"), byteEstimate = 300L),
                metaFixture("B", lastAccessedAt = Instant.parse("2026-02-01T00:00:00Z"), byteEstimate = 300L),
                metaFixture("C", lastAccessedAt = Instant.parse("2026-03-01T00:00:00Z"), byteEstimate = 600L),
            )
        every { cacheMetaRepository.listByLastAccessedAsc(Int.MAX_VALUE) } returns allMeta
        every { candleRepository.deleteByCodeAndInterval(any(), any()) } returns 5

        val result = sut.evictIfNeeded()

        // A 하나만 삭제하면 300 >= 220 → 종료
        assertThat(result.evictedCount).isEqualTo(1)
        assertThat(result.reclaimedBytes).isEqualTo(300L)

        verify(exactly = 1) { candleRepository.deleteByCodeAndInterval("A", Interval.M1) }
        verify(exactly = 0) { candleRepository.deleteByCodeAndInterval("B", Interval.M1) }
        verify(exactly = 0) { candleRepository.deleteByCodeAndInterval("C", Interval.M1) }
    }

    @Test
    fun `D1 interval은 eviction 대상 제외 — M1만 삭제`() {
        // total = 300 + 500 + 400 = 1200 bytes > maxBytes(1000)
        // excess = 200, target = 220 bytes
        // D1(300) 건너뜀, M1_A(500) 삭제 후 500 >= 220 → 종료 (M1_B 삭제 X)
        val d1Meta =
            metaFixture(
                "005930",
                interval = Interval.D1,
                lastAccessedAt = Instant.parse("2026-01-01T00:00:00Z"),
                byteEstimate = 300L,
            )
        val m1A =
            metaFixture(
                "A",
                interval = Interval.M1,
                lastAccessedAt = Instant.parse("2026-01-02T00:00:00Z"),
                byteEstimate = 500L,
            )
        val m1B =
            metaFixture(
                "B",
                interval = Interval.M1,
                lastAccessedAt = Instant.parse("2026-02-01T00:00:00Z"),
                byteEstimate = 400L,
            )
        val allMeta = listOf(d1Meta, m1A, m1B)

        every { cacheMetaRepository.listByLastAccessedAsc(Int.MAX_VALUE) } returns allMeta
        every { candleRepository.deleteByCodeAndInterval(any(), any()) } returns 7

        val result = sut.evictIfNeeded()

        // D1 제외, M1_A(500) 삭제 후 500 >= 220 → 종료 (M1_B 삭제 X)
        assertThat(result.evictedCount).isEqualTo(1)
        assertThat(result.reclaimedBytes).isEqualTo(500L)

        // D1은 삭제 안 함
        verify(exactly = 0) { candleRepository.deleteByCodeAndInterval("005930", Interval.D1) }
        verify(exactly = 0) { cacheMetaRepository.deleteByCodeAndInterval("005930", Interval.D1) }

        // M1_A 삭제, M1_B는 삭제 X
        verify(exactly = 1) { candleRepository.deleteByCodeAndInterval("A", Interval.M1) }
        verify(exactly = 0) { candleRepository.deleteByCodeAndInterval("B", Interval.M1) }
    }
}

package kr.spdaewoon.magicjar.api

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.config.ChartCandleProperties
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.service.CandleQueryService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import org.springframework.data.domain.Pageable
import org.springframework.data.redis.core.HashOperations
import org.springframework.data.redis.core.StringRedisTemplate

/**
 * CandleQueryService — `?limit=` 사용자 입력 cap 500 → 5000 확장 회귀 가드 (ADR 0093 §3-1 LOCK).
 *
 * Phase 3 (2026-05-20 backend-core):
 *   - maxLimit property 외부화 (ChartCandleProperties.maxResponseLimit default 5000)
 *   - limit.coerceIn(1, maxLimit) clamp 검증
 *   - default limit 600 (controller layer default도 600 정합)
 *
 * 진보 페르소나 (ADR 0087):
 *   - 💡 5000 cap 사용자 입력 그대로 통과 → pageable.pageSize 5000 (DB 위임)
 *   - 🔄 99999 cap 초과 입력 → 5000으로 clamp (방어적 LOCK)
 *   - ✨ 0 입력 → 1로 clamp (최소 안전 LOCK)
 */
class CandleQueryServiceLimitClampTest {
    private fun buildService(
        repository: CandleJpaRepository = mock<CandleJpaRepository>(),
        props: ChartCandleProperties = ChartCandleProperties(maxResponseLimit = 5000),
    ): CandleQueryService {
        val redisTemplate = mock<StringRedisTemplate>()
        val hashOps = mock<HashOperations<String, String, String>>()
        whenever(redisTemplate.opsForHash<String, String>()).thenReturn(hashOps)
        whenever(hashOps.entries(any())).thenReturn(emptyMap())
        return CandleQueryService(
            candleJpaRepository = repository,
            redisTemplate = redisTemplate,
            objectMapper = ObjectMapper(),
            chartCandleProperties = props,
        )
    }

    @Test
    fun `maxLimit getter — ChartCandleProperties maxResponseLimit 위임 (default 5000)`() {
        val service = buildService()

        assertThat(service.maxLimit).isEqualTo(5000)
        assertThat(service.defaultLimit).isEqualTo(600)
    }

    @Test
    fun `maxLimit getter — application yml override 3000 시 반영`() {
        val service = buildService(props = ChartCandleProperties(maxResponseLimit = 3000))

        assertThat(service.maxLimit).isEqualTo(3000)
    }

    @Test
    fun `getCandles — limit 5000 사용자 입력 그대로 DB 위임 (clamp 통과)`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = 5000)

        // pageable.pageSize 검증 — limit 5000 그대로 DB layer 위임.
        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(5000)
    }

    @Test
    fun `getCandles — limit 99999 cap 초과 → 5000으로 clamp`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = 99999)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        // clamp 적용 — 99999 → maxResponseLimit(5000).
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(5000)
    }

    @Test
    fun `getCandles — limit 0 이하 → 1로 clamp (방어적 최소)`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = 0)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        // 0 → coerceIn(1, 5000) → 1.
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(1)
    }

    @Test
    fun `getCandles — limit 음수 → 1로 clamp`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service = buildService(repository = repository)

        service.getCandles(code = "005930", interval = "1d", before = null, limit = -100)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(1)
    }

    @Test
    fun `getCandles — application yml override 시 max 3000 cap 적용`() {
        val repository = mock<CandleJpaRepository>()
        whenever(repository.findByCodeAndIntervalLatest(any(), any(), any())).thenReturn(emptyList<CandleJpaEntity>())
        val service =
            buildService(
                repository = repository,
                props = ChartCandleProperties(maxResponseLimit = 3000),
            )

        // 5000 입력이지만 maxResponseLimit=3000 override → 3000으로 clamp.
        service.getCandles(code = "005930", interval = "1d", before = null, limit = 5000)

        val pageableCaptor = argumentCaptor<Pageable>()
        verify(repository).findByCodeAndIntervalLatest(any(), any(), pageableCaptor.capture())
        assertThat(pageableCaptor.firstValue.pageSize).isEqualTo(3000)
    }
}

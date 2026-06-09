package kr.spdaewoon.magicjar.consumer.aggregation

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerAuditTrailJpaRepository
import kr.spdaewoon.magicjar.consumer.reconciliation.MarketDailyUniverseProvider
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * MarketDailyRollupService — 전종목 1wk/1mo/1y 1d 롤업 백필 단위테스트 (ADR 0120).
 *
 * 검증:
 *   - universe 0건 → EMPTY_UNIVERSE graceful (rollup 0).
 *   - 전종목 순회 — 종목별 rollupDetailed 호출 + 집계. 1d 없는 종목은 skippedNoDaily.
 */
class MarketDailyRollupServiceTest {
    private fun newService(
        provider: MarketDailyUniverseProvider,
        runner: DailyFamilyRollupRunner,
        auditRepo: ConsumerAuditTrailJpaRepository = mockk(relaxed = true),
    ) = MarketDailyRollupService(
        universeProvider = provider,
        rollupRunner = runner,
        auditRepository = auditRepo,
        objectMapper = ObjectMapper(),
    )

    @Test
    fun `universe 0건이면 EMPTY_UNIVERSE — rollup 0`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns emptyList() }
        val runner = mockk<DailyFamilyRollupRunner>(relaxed = true)
        val service = newService(provider, runner)

        val result = service.runRollup()

        assertThat(result.status).isEqualTo(MarketDailyRollupService.RollupStatus.EMPTY_UNIVERSE)
        verify(exactly = 0) { runner.rollupDetailed(any()) }
    }

    @Test
    fun `전종목 순회 — 1d 있는 종목 집계, 1d 없는 종목 skippedNoDaily`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("005930", "000660", "999999") }
        val runner = mockk<DailyFamilyRollupRunner>()
        // 005930·000660 은 1d 보유 → 1wk/1mo/1y 각 1봉 upsert. 999999 는 1d 없음 → emptyMap.
        every { runner.rollupDetailed("005930") } returns
            mapOf(
                "1wk" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 100, latestBar = null),
                "1mo" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 24, latestBar = null),
                "1y" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 3, latestBar = null),
            )
        every { runner.rollupDetailed("000660") } returns
            mapOf(
                "1wk" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 80, latestBar = null),
                "1mo" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 20, latestBar = null),
                "1y" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 2, latestBar = null),
            )
        every { runner.rollupDetailed("999999") } returns emptyMap()
        val service = newService(provider, runner)

        val result = service.runRollup()

        assertThat(result.status).isEqualTo(MarketDailyRollupService.RollupStatus.COMPLETED)
        assertThat(result.universeSize).isEqualTo(3)
        assertThat(result.processedSymbols).isEqualTo(2)
        assertThat(result.skippedNoDaily).isEqualTo(1)
        assertThat(result.weeklyUpserted).isEqualTo(180L) // 100 + 80
        assertThat(result.monthlyUpserted).isEqualTo(44L) // 24 + 20
        assertThat(result.yearlyUpserted).isEqualTo(5L) // 3 + 2
        assertThat(result.failedSymbols).isEqualTo(0)
    }

    @Test
    fun `interval 실패(-1) 종목은 failedSymbols 집계`() {
        val provider = mockk<MarketDailyUniverseProvider> { every { resolveCodes() } returns listOf("005930") }
        val runner = mockk<DailyFamilyRollupRunner>()
        every { runner.rollupDetailed("005930") } returns
            mapOf(
                "1wk" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 100, latestBar = null),
                "1mo" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = -1, latestBar = null), // 실패
                "1y" to DailyFamilyRollupRunner.IntervalRollupResult(upserted = 3, latestBar = null),
            )
        val service = newService(provider, runner)

        val result = service.runRollup()

        assertThat(result.processedSymbols).isEqualTo(1)
        assertThat(result.failedSymbols).isEqualTo(1)
        assertThat(result.weeklyUpserted).isEqualTo(100L)
        assertThat(result.yearlyUpserted).isEqualTo(3L)
    }
}

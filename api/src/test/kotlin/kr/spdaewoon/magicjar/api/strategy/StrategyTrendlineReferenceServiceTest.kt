package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyTrendlineRefJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyTrendlineRefJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyTrendlineReferenceService
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineRef
import org.junit.jupiter.api.Test

/**
 * [StrategyTrendlineReferenceService] — 참조 집합 diff 동기화 (ADR 0140 §3-3, spec R2).
 */
class StrategyTrendlineReferenceServiceTest {
    private val refRepo = mockk<StrategyTrendlineRefJpaRepository>(relaxed = true)
    private val service = StrategyTrendlineReferenceService(refRepo)

    init {
        // JpaRepository.save 제네릭 반환은 relaxed mock 이 합성 못 함(ClassCast) — echo 스텁.
        every { refRepo.save(any()) } answers { firstArg() }
    }

    private fun cond(id: Long) = Condition(IndicatorRef("close", emptyList()), Operator.GT, TrendlineRef(id))

    @Test
    fun `sync — desired와 current diff 계산해 add·remove만 반영`() {
        // entry: trendline(1) · exit: trendline(2) → desired = {1,2}
        // current DB = {2,4} → toAdd={1}, toRemove={4}, 2는 유지
        every { refRepo.findByStrategyId(5L) } returns
            listOf(
                StrategyTrendlineRefJpaEntity(strategyId = 5L, trendlineId = 2L),
                StrategyTrendlineRefJpaEntity(strategyId = 5L, trendlineId = 4L),
            )

        service.sync(strategyId = 5L, entryAst = cond(1L), exitAst = cond(2L))

        verify(exactly = 1) { refRepo.deleteByStrategyIdAndTrendlineId(5L, 4L) }
        verify(exactly = 0) { refRepo.deleteByStrategyIdAndTrendlineId(5L, 2L) }
        verify(exactly = 1) { refRepo.save(match { it.strategyId == 5L && it.trendlineId == 1L }) }
        verify(exactly = 0) { refRepo.save(match { it.trendlineId == 2L }) }
    }

    @Test
    fun `sync — 참조 없는 전략은 기존 참조 전부 제거`() {
        every { refRepo.findByStrategyId(9L) } returns
            listOf(StrategyTrendlineRefJpaEntity(strategyId = 9L, trendlineId = 7L))

        // 순수 indicator DSL(트렌드라인 0) → desired 빈 집합
        val noTrendline = Condition(IndicatorRef("ma", listOf("5")), Operator.GT, IndicatorRef("ma", listOf("20")))
        service.sync(strategyId = 9L, entryAst = noTrendline, exitAst = null)

        verify(exactly = 1) { refRepo.deleteByStrategyIdAndTrendlineId(9L, 7L) }
        verify(exactly = 0) { refRepo.save(any()) }
    }

    @Test
    fun `clearByStrategyId — soft delete 참조 정리`() {
        service.clearByStrategyId(5L)
        verify(exactly = 1) { refRepo.deleteByStrategyId(5L) }
    }
}

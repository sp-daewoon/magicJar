package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.web.server.ResponseStatusException

class StrategyServiceActivationGateTest {
    private val strategyRepositoryPort = mockk<StrategyRepositoryPort>(relaxed = true)
    private val strategyJpaRepository = mockk<StrategyJpaRepository>(relaxed = true)
    private val backtestRunJpaRepository = mockk<BacktestRunJpaRepository>(relaxed = true)
    private val redisStrategyCache = mockk<RedisStrategyCache>(relaxed = true)
    private val service =
        StrategyService(
            strategyRepositoryPort = strategyRepositoryPort,
            strategyJpaRepository = strategyJpaRepository,
            backtestRunJpaRepository = backtestRunJpaRepository,
            redisStrategyCache = redisStrategyCache,
            astDepthLimit = 32,
            nodeCountLimit = 10_000,
            indicatorCallLimit = 50,
        )

    @Test
    fun `PAUSED to ACTIVE는 성공한 백테스트 없으면 차단한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") } returns false

        assertThatThrownBy { service.toggleStatus(1L, StrategyStatus.ACTIVE) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("성공한 백테스트")

        verify(exactly = 0) { strategyJpaRepository.updateStatus(1L, StrategyStatus.ACTIVE.name) }
    }

    @Test
    fun `성공한 백테스트가 있으면 ACTIVE 전환을 허용한다`() {
        every { strategyJpaRepository.findByIdAndStatusNot(1L, StrategyStatus.DELETED.name) } returns entity(status = "PAUSED")
        every { backtestRunJpaRepository.existsAutoActivationReadyRun(1L, "1234") } returns true
        every { strategyJpaRepository.updateStatus(1L, StrategyStatus.ACTIVE.name) } returns 1
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.ACTIVE)

        service.toggleStatus(1L, StrategyStatus.ACTIVE)

        verify(exactly = 1) { strategyJpaRepository.updateStatus(1L, StrategyStatus.ACTIVE.name) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    private fun entity(status: String) =
        StrategyJpaEntity(
            id = 1L,
            userId = 1234L,
            name = "test",
            nameKo = "테스트",
            dslEntry = "close > ma(20)",
            dslExit = null,
            astDepth = 1,
            nodeCount = 1,
            indicatorCallCount = 1,
            estimatedEvalTimeMs = 1,
            complexityClass = ComplexityClass.LOW.name,
            status = status,
        )

    private fun strategy(status: StrategyStatus) =
        Strategy(
            id = 1L,
            userId = 1234L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                    universe = Universe("KOSPI"),
                    tf = "D1",
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 1,
                    indicatorCallCount = 1,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
            status = status,
        )
}

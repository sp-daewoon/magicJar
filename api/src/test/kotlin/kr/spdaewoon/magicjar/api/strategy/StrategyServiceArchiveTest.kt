package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.cache.RedisStrategyCache
import kr.spdaewoon.magicjar.api.persistence.backtest.BacktestRunJpaRepository
import kr.spdaewoon.magicjar.api.persistence.order.OrderRequestJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Instant

/**
 * V98 전략 아카이브 — archive/unarchive 가드 + 상태 전환 단위 테스트.
 *
 * 자본 안전: 보유 포지션·미체결 주문이 있으면 보관을 거부(409)해 관리 주체 없는 포지션 고아화를 막는다.
 * 보관 해제는 PAUSED로 복귀(즉시 자동매매 재개 금지 — 보수적).
 */
class StrategyServiceArchiveTest {
    private val strategyRepositoryPort = mockk<StrategyRepositoryPort>(relaxed = true)
    private val strategyJpaRepository = mockk<StrategyJpaRepository>(relaxed = true)
    private val backtestRunJpaRepository = mockk<BacktestRunJpaRepository>(relaxed = true)
    private val redisStrategyCache = mockk<RedisStrategyCache>(relaxed = true)
    private val positionReadRepo = mockk<StrategyPositionReadJpaRepository>(relaxed = true)
    private val orderRequestRepo = mockk<OrderRequestJpaRepository>(relaxed = true)

    private val service =
        StrategyService(
            strategyRepositoryPort = strategyRepositoryPort,
            strategyJpaRepository = strategyJpaRepository,
            backtestRunJpaRepository = backtestRunJpaRepository,
            redisStrategyCache = redisStrategyCache,
            astDepthLimit = 32,
            nodeCountLimit = 10_000,
            indicatorCallLimit = 50,
            strategyPositionReadJpaRepository = positionReadRepo,
            orderRequestJpaRepository = orderRequestRepo,
        )

    @Test
    fun `보유 포지션이 있으면 보관을 409로 거부한다`() {
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.ACTIVE)
        every { positionReadRepo.findByStrategyId(1L) } returns listOf(position(quantity = 2))

        assertThatThrownBy { service.archive(1L, USER_ID) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("보유 포지션 2주")

        verify(exactly = 0) { strategyJpaRepository.archive(1L) }
        verify(exactly = 0) { redisStrategyCache.invalidateAll() }
    }

    @Test
    fun `미체결 주문이 있으면 보관을 409로 거부한다`() {
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.PAUSED)
        every { positionReadRepo.findByStrategyId(1L) } returns emptyList()
        every { orderRequestRepo.countOpenByStrategyId(1L) } returns 3L

        assertThatThrownBy { service.archive(1L, USER_ID) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("미체결 주문 3건")

        verify(exactly = 0) { strategyJpaRepository.archive(1L) }
    }

    @Test
    fun `보유·미체결 0이면 보관을 허용하고 ARCHIVED로 전환한다`() {
        every { strategyRepositoryPort.findById(1L) } returnsMany
            listOf(strategy(status = StrategyStatus.ACTIVE), strategy(status = StrategyStatus.ARCHIVED))
        every { positionReadRepo.findByStrategyId(1L) } returns listOf(position(quantity = 0))
        every { orderRequestRepo.countOpenByStrategyId(1L) } returns 0L

        val result = service.archive(1L, USER_ID)

        assertThat(result.status).isEqualTo(StrategyStatus.ARCHIVED)
        verify(exactly = 1) { strategyJpaRepository.archive(1L) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    @Test
    fun `타인 소유 전략 보관은 404로 거부한다`() {
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.ACTIVE)

        assertThatThrownBy { service.archive(1L, USER_ID + 999) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasFieldOrPropertyWithValue("statusCode", HttpStatus.NOT_FOUND)

        verify(exactly = 0) { strategyJpaRepository.archive(1L) }
        // 소유권 검증이 포지션 조회보다 먼저 — 타인 전략 포지션은 조회하지 않는다.
        verify(exactly = 0) { positionReadRepo.findByStrategyId(1L) }
    }

    @Test
    fun `보관 해제는 ARCHIVED에서 PAUSED로 복귀시킨다`() {
        every { strategyRepositoryPort.findById(1L) } returnsMany
            listOf(strategy(status = StrategyStatus.ARCHIVED), strategy(status = StrategyStatus.PAUSED))

        val result = service.unarchive(1L, USER_ID)

        assertThat(result.status).isEqualTo(StrategyStatus.PAUSED)
        verify(exactly = 1) { strategyJpaRepository.unarchive(1L) }
        verify(exactly = 1) { redisStrategyCache.invalidateAll() }
    }

    @Test
    fun `ARCHIVED 상태가 아니면 보관 해제를 409로 거부한다`() {
        every { strategyRepositoryPort.findById(1L) } returns strategy(status = StrategyStatus.ACTIVE)

        assertThatThrownBy { service.unarchive(1L, USER_ID) }
            .isInstanceOf(ResponseStatusException::class.java)
            .hasMessageContaining("보관 상태(ARCHIVED)가 아닌")

        verify(exactly = 0) { strategyJpaRepository.unarchive(1L) }
    }

    private fun position(quantity: Int) =
        StrategyPositionReadJpaEntity(
            id = 1L,
            userId = USER_ID.toString(),
            strategyId = 1L,
            code = "005930",
            quantity = quantity,
            avgPrice = BigDecimal("70000"),
        )

    private fun strategy(status: StrategyStatus) =
        Strategy(
            id = 1L,
            userId = USER_ID,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                    universe = Universe("KOSPI"),
                    tf = "D1",
                    fixedStopLoss = BigDecimal("5.00"),
                    takeProfitPct = BigDecimal("10.00"),
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
            codes = listOf("005930"),
            intervals = listOf("1d"),
            archivedAt = if (status == StrategyStatus.ARCHIVED) Instant.now() else null,
        )

    companion object {
        private const val USER_ID = 1234L
    }
}

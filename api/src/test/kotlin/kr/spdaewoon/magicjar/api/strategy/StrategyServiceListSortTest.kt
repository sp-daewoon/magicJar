package kr.spdaewoon.magicjar.api.strategy

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
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
import kr.spdaewoon.magicjar.domain.strategy.StrategyOrigin
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.domain.PageImpl
import org.springframework.data.domain.Pageable
import java.math.BigDecimal
import java.time.Instant

/**
 * "내 전략" 목록 정렬 검증 — ACTIVE 우선 + 그룹 내 updatedAt DESC.
 *
 * 정렬 본체는 [StrategyJpaRepository.findAllByUserIdActiveFirst] @Query JPQL CASE 식이 담당한다.
 * 본 테스트는 [StrategyService.listByUserId] 가
 *   1. createdAt Sort 가 박힌 기존 [StrategyJpaRepository.findAllByUserIdAndStatusNot] 를 더 이상 호출하지 않고
 *   2. 신규 ACTIVE-우선 메서드를 Sort 미지정 Pageable 로 호출하며(정렬은 @Query 위임)
 *   3. repository 가 돌려준 정렬 순서를 그대로 보존하는지
 * 를 검증한다. JPQL CASE 식 평가 정확성(ACTIVE 가 createdAt 더 오래됐어도 맨 앞)은
 * repository 반환 순서를 fixture 로 재현해 service 가 순서를 깨지 않음을 확인한다.
 */
class StrategyServiceListSortTest {
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
    fun `listByUserId는 ACTIVE 우선 메서드를 Sort 미지정 Pageable로 호출하고 createdAt 정렬 메서드는 쓰지 않는다`() {
        val pageable = slot<Pageable>()
        every {
            strategyJpaRepository.findAllByUserIdActiveFirst(1234L, capture(pageable))
        } returns PageImpl(emptyList())

        service.listByUserId(userId = 1234L, page = 0, size = 20)

        // 정렬은 @Query 가 담당 — Pageable 에 Sort 가 박히면 ORDER BY 와 충돌하므로 unsorted 여야 한다.
        assertThat(pageable.captured.sort.isSorted).isFalse()
        assertThat(pageable.captured.pageNumber).isEqualTo(0)
        assertThat(pageable.captured.pageSize).isEqualTo(20)

        // 기존 createdAt DESC 메서드는 더 이상 목록 조회에 쓰이지 않는다.
        verify(exactly = 0) { strategyJpaRepository.findAllByUserIdAndStatusNot(any(), any(), any()) }
        verify(exactly = 1) { strategyJpaRepository.findAllByUserIdActiveFirst(1234L, any()) }
    }

    @Test
    fun `ACTIVE가 createdAt 더 오래됐어도 맨 앞에 오고 그룹 내 updatedAt DESC 순서를 보존한다`() {
        // repository(@Query) 가 ACTIVE 우선 + updatedAt DESC 로 돌려준 순서를 fixture 로 재현.
        // id 10: ACTIVE, 가장 오래 전에 생성됐지만 최근 토글(updated_at 최신) → 맨 앞이어야 한다.
        // id 11: BUY_BLOCKED, id 12: PAUSED → 가동도 우선순위로 뒤따른다.
        val active = entity(id = 10L, status = "ACTIVE", createdAt = days(1), updatedAt = days(30))
        val buyBlocked = entity(id = 11L, status = "BUY_BLOCKED", createdAt = days(20), updatedAt = days(20))
        val pausedNew = entity(id = 12L, status = "PAUSED", createdAt = days(25), updatedAt = days(28))
        val pausedOld = entity(id = 13L, status = "PAUSED", createdAt = days(26), updatedAt = days(10))
        val orderedFromRepo = listOf(active, buyBlocked, pausedNew, pausedOld)

        every {
            strategyJpaRepository.findAllByUserIdActiveFirst(1234L, any())
        } returns PageImpl(orderedFromRepo)
        orderedFromRepo.forEach { e ->
            every { strategyRepositoryPort.findById(e.id!!) } returns strategy(id = e.id!!)
        }

        val result = service.listByUserId(userId = 1234L, page = 0, size = 20)

        // service 는 repository 정렬 순서를 도메인 매핑하면서 보존해야 한다.
        assertThat(result.content.map { it.id }).containsExactly(10L, 11L, 12L, 13L)
    }

    private fun days(n: Long): Instant = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(n * 86_400)

    private fun entity(
        id: Long,
        status: String,
        createdAt: Instant,
        updatedAt: Instant,
    ) = StrategyJpaEntity(
        id = id,
        userId = 1234L,
        name = "test-$id",
        nameKo = "테스트-$id",
        dslEntry = "close > ma(20)",
        dslExit = null,
        astDepth = 1,
        nodeCount = 1,
        indicatorCallCount = 1,
        estimatedEvalTimeMs = 1,
        complexityClass = ComplexityClass.LOW.name,
        status = status,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    private fun strategy(id: Long) =
        Strategy(
            id = id,
            userId = 1234L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test-$id", buyCondition = null),
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
            status = StrategyStatus.ACTIVE,
            origin = StrategyOrigin.USER_DSL,
            codes = listOf("005930"),
            intervals = listOf("1d"),
        )
}

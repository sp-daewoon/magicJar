package kr.spdaewoon.magicjar.api

import kr.spdaewoon.magicjar.api.controller.StrategiesController
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyPositionReadJpaRepository
import kr.spdaewoon.magicjar.api.service.market.LivePriceCacheReader
import kr.spdaewoon.magicjar.api.service.strategy.StrategyService
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.Universe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.strategy.KisYamlDslParser
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.catchThrowable
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * GET /api/strategies/{id}/scalein-plan 분할 매수 계획 엔드포인트 검증 (ADR 0128 R3).
 *
 * 계약(frontend R3 LOCK):
 * - 박제 base(보유 중) → 회차별 targetQuantity 정확 계산. 미진입(미박제) → null(비중·조건 미리 보기).
 * - 0% 비중 회차 제외(⑨). floor 도달가는 알람만(⑦). expectedStopLossPrice = 평단 × (1 − 손절%/100).
 * - 분할 미설정 전략 → 400. 전략 없음 → 404.
 *
 * 룰 5: LLM provider import 0건.
 */
class StrategyScaleInPlanEndpointTest {
    private val strategyService = mock<StrategyService>()
    private val kisYamlDslParser = mock<KisYamlDslParser>()
    private val instrumentJpaRepository = mock<InstrumentJpaRepository>()
    private val positionRepo = mock<StrategyPositionReadJpaRepository>()
    private val livePriceCacheReader = mock<LivePriceCacheReader>()
    private val strategyStartService = mock<kr.spdaewoon.magicjar.api.service.strategy.StrategyStartService>()
    private val favoritePort = mock<kr.spdaewoon.magicjar.domain.strategy.port.StrategyFavoritePort>()

    private val controller =
        StrategiesController(
            strategyService = strategyService,
            kisYamlDslParser = kisYamlDslParser,
            instrumentJpaRepository = instrumentJpaRepository,
            strategyPositionReadJpaRepository = positionRepo,
            livePriceCacheReader = livePriceCacheReader,
            strategyStartService = strategyStartService,
            favoritePort = favoritePort,
        )

    private val threeSlicePlan =
        ScaleInPlan(
            totalSlices = 3,
            sliceWeights = listOf(BigDecimal("40"), BigDecimal("35"), BigDecimal("25")),
            reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
            stopLossFloorPct = BigDecimal("30"),
            minSliceIntervalSec = 180,
        )

    private fun strategyWith(
        scaleIn: ScaleInPlan?,
        fixedStopLoss: BigDecimal? = BigDecimal("5"),
    ): Strategy {
        val definition =
            StrategyDefinition(
                dsl = DslStrategyDefinition(name = "qa_scalein", nameKo = "분할매수 검증"),
                universe = Universe("KOSPI"),
                tf = "D1",
                fixedStopLoss = fixedStopLoss,
                scaleIn = scaleIn,
            )
        return Strategy(
            id = 1L,
            userId = 1L,
            definition = definition,
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 1,
                    indicatorCallCount = 0,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
        )
    }

    private fun heldEntity(
        code: String = "005930",
        quantity: Int = 4,
        avgPrice: BigDecimal = BigDecimal("10000.0000"),
        filledLots: Int = 1,
        base: BigDecimal? = BigDecimal("1000000.0000"),
    ) = StrategyPositionReadJpaEntity(
        id = 1L,
        userId = "1",
        strategyId = 1L,
        code = code,
        quantity = quantity,
        avgPrice = avgPrice,
        filledLots = filledLots,
        realizedPnl = BigDecimal.ZERO,
        scaleInBaseBudget = base,
    )

    @Test
    fun `박제 base 보유 — 회차별 목표 수량을 고정 base 비중으로 정확 계산`() {
        whenever(strategyService.findById(1L)).thenReturn(strategyWith(threeSlicePlan))
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(heldEntity(avgPrice = BigDecimal("10000.0000"))))

        // currentPrice=10000 명시 — base 100만 × [40,35,25]% / 10000 = [40, 35, 25]주
        val res = controller.getScaleInPlan(1L, currentPrice = BigDecimal("10000"))

        assertThat(res.held).isTrue()
        assertThat(res.baseBudget).isEqualByComparingTo("1000000.0000")
        assertThat(res.effectiveSlices).isEqualTo(3)
        assertThat(res.slices.map { it.targetQuantity }).containsExactly(40L, 35L, 25L)
        assertThat(res.slices.map { it.displayOrder }).containsExactly(1, 2, 3)
        // 예상 손절가 = 평단 10000 × (1 − 5/100) = 9500
        assertThat(res.expectedStopLossPrice).isEqualByComparingTo("9500.0000")
        // floor 도달가 = 10000 × (1 − 30/100) = 7000 (알람만)
        assertThat(res.floorPrice).isEqualByComparingTo("7000.0000")
        assertThat(res.floorPct).isEqualByComparingTo("30")
    }

    @Test
    fun `미진입(미박제) — 비중·진입 조건만 노출하고 목표 수량은 null(미리 보기)`() {
        whenever(strategyService.findById(1L)).thenReturn(strategyWith(threeSlicePlan))
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(emptyList())
        whenever(livePriceCacheReader.resolvePrices(any())).thenReturn(emptyMap())

        val res = controller.getScaleInPlan(1L, currentPrice = null)

        assertThat(res.held).isFalse()
        assertThat(res.baseBudget).isNull()
        assertThat(res.effectiveSlices).isEqualTo(3)
        assertThat(res.slices.map { it.targetQuantity }).containsExactly(null, null, null)
        assertThat(res.slices.first().triggerDescription).contains("1차")
        // 평단·기준가 없음 → 가격류 null
        assertThat(res.expectedStopLossPrice).isNull()
        assertThat(res.floorPrice).isNull()
    }

    @Test
    fun `weight 0 회차 — 50_50_0 은 2회차만 노출(0퍼센트 skip)`() {
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = listOf(BigDecimal("50"), BigDecimal("50"), BigDecimal.ZERO))
        whenever(strategyService.findById(1L)).thenReturn(strategyWith(plan))
        whenever(positionRepo.findByStrategyId(1L)).thenReturn(listOf(heldEntity(avgPrice = BigDecimal("10000.0000"))))

        val res = controller.getScaleInPlan(1L, currentPrice = BigDecimal("10000"))

        assertThat(res.totalSlices).isEqualTo(3)
        assertThat(res.effectiveSlices).isEqualTo(2)
        assertThat(res.slices).hasSize(2)
        // base 100만 × [50,50]% / 10000 = [50, 50]주
        assertThat(res.slices.map { it.targetQuantity }).containsExactly(50L, 50L)
    }

    @Test
    fun `분할 미설정 전략 — 400`() {
        whenever(strategyService.findById(1L)).thenReturn(strategyWith(scaleIn = null))

        val thrown = catchThrowable { controller.getScaleInPlan(1L, currentPrice = null) }

        assertThat(thrown).isInstanceOf(ResponseStatusException::class.java)
        assertThat((thrown as ResponseStatusException).statusCode.value()).isEqualTo(400)
    }

    @Test
    fun `전략 없음 — 404`() {
        whenever(strategyService.findById(99L)).thenReturn(null)

        val thrown = catchThrowable { controller.getScaleInPlan(99L, currentPrice = null) }

        assertThat(thrown).isInstanceOf(ResponseStatusException::class.java)
        assertThat((thrown as ResponseStatusException).statusCode.value()).isEqualTo(404)
    }
}

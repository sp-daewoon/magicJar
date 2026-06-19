package kr.spdaewoon.magicjar.strategy.scalein

import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [ScaleInPlanProjector] 단위 테스트 — frontend 계약(0% 회차 제외·목표 수량·예상 손절가·floor가).
 */
class ScaleInPlanProjectorTest {
    private fun bd(v: String) = BigDecimal(v)

    @Test
    fun `회차별 목표 수량 = floor(base x weight pct div price) + 1차 즉시 trigger`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                sliceWeights = listOf(bd("40"), bd("35"), bd("25")),
                reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
            )
        val preview =
            ScaleInPlanProjector.project(
                plan = plan,
                fixedStopLossPct = bd("5"),
                baseBudget = bd("1000000"),
                referencePrice = bd("10000"),
                referenceAvgPrice = bd("10000"),
            )

        assertEquals(3, preview.slices.size)
        assertEquals(40L, preview.slices[0].targetQuantity) // 1,000,000×40% / 10,000 = 40
        assertEquals(35L, preview.slices[1].targetQuantity)
        assertEquals(25L, preview.slices[2].targetQuantity)
        assertTrue(preview.slices[0].triggerDescription.contains("즉시"))
        assertTrue(preview.slices[1].triggerDescription.contains("재발화"))
    }

    @Test
    fun `⑨ 0퍼센트 회차는 계획에서 제외(50_50_0 → 2 slices)`() {
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("50"), bd("50"), bd("0")))
        val preview =
            ScaleInPlanProjector.project(
                plan = plan,
                fixedStopLossPct = bd("5"),
                baseBudget = bd("1000000"),
                referencePrice = bd("10000"),
                referenceAvgPrice = bd("10000"),
            )
        assertEquals(2, preview.effectiveSlices)
        assertEquals(2, preview.slices.size)
        assertEquals(listOf(1, 2), preview.slices.map { it.displayOrder })
        assertEquals(listOf(0, 1), preview.slices.map { it.sliceIndex })
    }

    @Test
    fun `예상 손절가 = 평단×(1-손절퍼센트) + floor가 = 평단×(1-floor퍼센트)`() {
        val plan = ScaleInPlan(totalSlices = 2, stopLossFloorPct = bd("15"))
        val preview =
            ScaleInPlanProjector.project(
                plan = plan,
                fixedStopLossPct = bd("5"),
                baseBudget = bd("1000000"),
                referencePrice = bd("10000"),
                referenceAvgPrice = bd("10000"),
            )
        assertEquals(0, preview.expectedStopLossPrice!!.compareTo(bd("9500"))) // 10000×0.95
        assertEquals(0, preview.floorPrice!!.compareTo(bd("8500"))) // 10000×0.85
        assertEquals(0, preview.floorPct.compareTo(bd("15")))
    }

    @Test
    fun `1차 미진입(base·price 미정) — 비중·조건만, 가격류는 null`() {
        val plan = ScaleInPlan(totalSlices = 2)
        val preview =
            ScaleInPlanProjector.project(
                plan = plan,
                fixedStopLossPct = null,
                baseBudget = null,
                referencePrice = null,
                referenceAvgPrice = null,
            )
        assertNull(preview.slices[0].targetQuantity)
        assertNull(preview.expectedStopLossPrice)
        assertNull(preview.floorPrice)
        assertTrue(preview.slices.all { it.weightPct > BigDecimal.ZERO })
    }
}

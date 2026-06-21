package kr.spdaewoon.magicjar.domain.strategy

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal

/**
 * R1 ScaleInPlan·PartialExit·StrategyDefinition 검증 로직 단위 테스트 (사용자 결재 상수 2026-06-05).
 * R1 테스트 0건 OK(개발 룰 4)이나 검증 로직은 자본 경로 전제라 회귀 테스트를 둔다.
 */
class ScaleInExitValidationTest {
    private fun bd(v: String) = BigDecimal(v)

    // ── ScaleInPlan: totalSlices cap 1..10 ─────────────────────────────
    @Test
    fun `totalSlices within 1 to 10 ok`() {
        ScaleInPlan(totalSlices = 1).totalSlices shouldBe 1
        ScaleInPlan(totalSlices = 10).totalSlices shouldBe 10
    }

    @Test
    fun `totalSlices below 1 rejected`() {
        assertThrows<IllegalArgumentException> { ScaleInPlan(totalSlices = 0) }
    }

    @Test
    fun `totalSlices above 10 rejected`() {
        assertThrows<IllegalArgumentException> { ScaleInPlan(totalSlices = 11) }
    }

    // ── ScaleInPlan: sliceWeights size + sum ───────────────────────────
    @Test
    fun `sliceWeights count must equal totalSlices`() {
        assertThrows<IllegalArgumentException> {
            ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("50"), bd("50")))
        }
    }

    @Test
    fun `sliceWeights sum must be 100 within tolerance`() {
        ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("50"), bd("30"), bd("20")))
        ScaleInPlan(totalSlices = 2, sliceWeights = listOf(bd("50.005"), bd("49.995")))
        assertThrows<IllegalArgumentException> {
            ScaleInPlan(totalSlices = 2, sliceWeights = listOf(bd("60"), bd("30")))
        }
    }

    @Test
    fun `sliceWeights must be non-negative (negative rejected)`() {
        assertThrows<IllegalArgumentException> {
            ScaleInPlan(totalSlices = 2, sliceWeights = listOf(bd("110"), bd("-10")))
        }
    }

    // ⑨ weight 0 회차 허용 — [50,50,0] 유효(엔진이 skip). 음수만 금지.
    @Test
    fun `⑨ weight 0 slice is allowed (50_50_0 valid)`() {
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("50"), bd("50"), bd("0")))
        plan.effectiveSliceCount() shouldBe 2
        plan.sliceAt(0) shouldBe (0 to bd("50"))
        plan.sliceAt(1) shouldBe (1 to bd("50"))
        plan.sliceAt(2) shouldBe null // 0% 회차 skip → 진입 종료
    }

    @Test
    fun `⑨ sliceAt skips middle 0 weight (50_0_50 maps filledLots 1 to index 2)`() {
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("50"), bd("0"), bd("50")))
        plan.effectiveSliceCount() shouldBe 2
        plan.sliceAt(0) shouldBe (0 to bd("50"))
        plan.sliceAt(1) shouldBe (2 to bd("50"))
        plan.sliceAt(2) shouldBe null
    }

    @Test
    fun `⑧ sliceAt returns null beyond effective slice count`() {
        val plan = ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("40"), bd("35"), bd("25")))
        plan.sliceAt(3) shouldBe null
        plan.sliceAt(5) shouldBe null
    }

    // ── ⑦ catastrophic floor (전략 설정값) ──────────────────────────────
    @Test
    fun `floor pct defaults to 15 and rejects negative`() {
        ScaleInPlan(totalSlices = 3).resolvedFloorPct() shouldBe bd("15")
        ScaleInPlan(totalSlices = 3, stopLossFloorPct = bd("10")).resolvedFloorPct() shouldBe bd("10")
        assertThrows<IllegalArgumentException> {
            ScaleInPlan(totalSlices = 3, stopLossFloorPct = bd("-1"))
        }
    }

    @Test
    fun `validateFloorBelowDeepestEntry — floor too shallow for price-drop plan is rejected`() {
        // PRICE_DROP_PCT -10%, 3분할 → 최저 진입 누적 하락 ≈ 1-(0.9)^2 = 19%. floor 15% < 19% → 위반.
        val shallow =
            ScaleInPlan(
                totalSlices = 3,
                reentryTrigger = ReentryTrigger.PRICE_DROP_PCT,
                priceDropPct = bd("-10"),
                stopLossFloorPct = bd("15"),
            )
        ScaleInLimits.validateFloorBelowDeepestEntry(shallow)!!.contains("deeper") shouldBe true

        // floor 25% > 19% → 통과.
        val deep = shallow.copy(stopLossFloorPct = bd("25"))
        ScaleInLimits.validateFloorBelowDeepestEntry(deep) shouldBe null

        // SIGNAL_REPEAT 은 가격 조건 없음 → 항상 통과.
        val signalRepeat = ScaleInPlan(totalSlices = 3, stopLossFloorPct = bd("5"))
        ScaleInLimits.validateFloorBelowDeepestEntry(signalRepeat) shouldBe null
    }

    // ── 갭② floorAutoLiquidate 토글 (ADR 0128 amend) ────────────────────
    @Test
    fun `갭② floorAutoLiquidate defaults to false (alarm-only — zero-regression)`() {
        ScaleInPlan(totalSlices = 3).floorAutoLiquidate shouldBe false
    }

    @Test
    fun `갭② floorAutoLiquidate explicit true is preserved`() {
        ScaleInPlan(totalSlices = 3, floorAutoLiquidate = true).floorAutoLiquidate shouldBe true
        // copy 라운드트립으로 jackson 평탄 직렬화·역직렬화 호환(어노테이션 0) 전제 확인.
        ScaleInPlan(totalSlices = 3).copy(floorAutoLiquidate = true).floorAutoLiquidate shouldBe true
    }

    // ── ScaleInPlan: PRICE_DROP_PCT requires negative priceDropPct ─────
    @Test
    fun `price drop trigger requires negative priceDropPct`() {
        ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.PRICE_DROP_PCT, priceDropPct = bd("-3.0"))
        assertThrows<IllegalArgumentException> {
            ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.PRICE_DROP_PCT, priceDropPct = null)
        }
        assertThrows<IllegalArgumentException> {
            ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.PRICE_DROP_PCT, priceDropPct = bd("3.0"))
        }
    }

    @Test
    fun `signal repeat is the default trigger`() {
        ScaleInPlan(totalSlices = 3).reentryTrigger shouldBe ReentryTrigger.SIGNAL_REPEAT
    }

    @Test
    fun `default min slice interval is 60`() {
        ScaleInPlan(totalSlices = 2).minSliceIntervalSec shouldBe 60
    }

    // ── ScaleInPlan.resolvedWeights: even split + absorb remainder ─────
    @Test
    fun `resolvedWeights uses explicit weights when present`() {
        val w = ScaleInPlan(totalSlices = 3, sliceWeights = listOf(bd("50"), bd("30"), bd("20"))).resolvedWeights()
        w shouldBe listOf(bd("50"), bd("30"), bd("20"))
    }

    @Test
    fun `resolvedWeights even split sums to exactly 100`() {
        val w = ScaleInPlan(totalSlices = 3).resolvedWeights()
        w shouldHaveSize 3
        w.fold(BigDecimal.ZERO, BigDecimal::add).compareTo(bd("100")) shouldBe 0
    }

    // ── PartialExit single validation ──────────────────────────────────
    @Test
    fun `partialExit valid`() {
        PartialExit(triggerProfitPct = bd("3.0"), exitWeightPct = bd("50"))
    }

    @Test
    fun `partialExit non-positive trigger rejected`() {
        assertThrows<IllegalArgumentException> { PartialExit(bd("0"), bd("50")) }
    }

    @Test
    fun `partialExit weight out of range rejected`() {
        assertThrows<IllegalArgumentException> { PartialExit(bd("3"), bd("0")) }
        assertThrows<IllegalArgumentException> { PartialExit(bd("3"), bd("100.01")) }
    }

    // ── ExitLimits.validatePartialExits: stage cap 5 + weight sum <= 100 ─
    @Test
    fun `partialExits up to 5 stages ok`() {
        val five = (1..5).map { PartialExit(bd("$it"), bd("20")) }
        ExitLimits.validatePartialExits(five)
    }

    @Test
    fun `partialExits over 5 stages rejected`() {
        val six = (1..6).map { PartialExit(bd("$it"), bd("10")) }
        assertThrows<IllegalArgumentException> { ExitLimits.validatePartialExits(six) }
    }

    @Test
    fun `partialExits weight sum over 100 rejected`() {
        val over = listOf(PartialExit(bd("3"), bd("60")), PartialExit(bd("7"), bd("50")))
        assertThrows<IllegalArgumentException> { ExitLimits.validatePartialExits(over) }
    }

    // ── StrategyDefinition: backward compat + cross validation ─────────
    @Test
    fun `strategyDefinition null scaleIn and partialExits is backward compatible`() {
        val def = StrategyDefinition(dsl = DslStrategyDefinition(name = "t"))
        def.scaleIn shouldBe null
        def.partialExits shouldBe null
    }

    @Test
    fun `strategyDefinition rejects over-cap partialExits`() {
        val six = (1..6).map { PartialExit(bd("$it"), bd("10")) }
        assertThrows<IllegalArgumentException> {
            StrategyDefinition(dsl = DslStrategyDefinition(name = "t"), partialExits = six)
        }
    }
}

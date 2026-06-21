package kr.spdaewoon.magicjar.strategy.scalein

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [ScaleInExitCalculator] 핵심 분기 단위 테스트 (순수 함수 — 결정론·저비용).
 *
 * 우선순위 LOCK(spec §3-3): STOP_LOSS > PARTIAL_TP > TAKE_PROFIT > ENTER_SLICE > HOLD.
 */
class ScaleInExitCalculatorTest {
    private val now = Instant.parse("2026-06-05T01:00:00Z")

    private fun position(
        quantity: Int,
        avgPrice: String,
        filledLots: Int = 1,
        executedExitStages: Set<Int> = emptySet(),
    ) = StrategyPosition(
        userId = "u1",
        strategyId = 10L,
        code = "005930",
        quantity = quantity,
        avgPrice = BigDecimal(avgPrice),
        filledLots = filledLots,
        executedExitStages = executedExitStages,
        openedAt = now,
        updatedAt = now,
    )

    @Test
    fun `stop-loss wins over take-profit on simultaneous touch`() {
        // 평단 1000, stop 5%(=950), take 1%. 현재가 940 → stop·take 동시 조건이지만 stop 우선
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000"),
                    currentPrice = BigDecimal("940"),
                    fixedStopLossPct = BigDecimal("5"),
                    takeProfitPct = BigDecimal("1"),
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
        assertEquals(10, (decision as ScaleInExitDecision.StopLoss).quantity)
    }

    @Test
    fun `partial take-profit fires only unexecuted stage and picks highest reached trigger`() {
        // 단계0: trigger 3% weight 50, 단계1: trigger 7% weight 30. 평단 1000, 현재가 1080 → +8% → 둘 다 도달
        // 가장 높은 trigger(단계1) 선택. 보유 10 × 30% = 3
        val partials =
            listOf(
                PartialExit(triggerProfitPct = BigDecimal("3"), exitWeightPct = BigDecimal("50")),
                PartialExit(triggerProfitPct = BigDecimal("7"), exitWeightPct = BigDecimal("30")),
            )
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000"),
                    currentPrice = BigDecimal("1080"),
                    takeProfitPct = BigDecimal("50"), // 전량 익절 미도달
                    partialExits = partials,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.PartialTakeProfit)
        decision as ScaleInExitDecision.PartialTakeProfit
        assertEquals(1, decision.stageIndex)
        assertEquals(3, decision.quantity)
    }

    @Test
    fun `executed partial stage is not re-fired`() {
        // 단계0 이미 발화(executedExitStages={0}). 현재가 +4% → 단계0만 도달했으나 이미 발화 → HOLD
        val partials = listOf(PartialExit(triggerProfitPct = BigDecimal("3"), exitWeightPct = BigDecimal("50")))
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", executedExitStages = setOf(0)),
                    currentPrice = BigDecimal("1040"),
                    partialExits = partials,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `take-profit sells remaining quantity`() {
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 7, avgPrice = "1000"),
                    currentPrice = BigDecimal("1100"),
                    takeProfitPct = BigDecimal("5"),
                ),
            )
        assertTrue(decision is ScaleInExitDecision.TakeProfit)
        assertEquals(7, (decision as ScaleInExitDecision.TakeProfit).quantity)
    }

    @Test
    fun `scale-in next slice enters on buy signal when filled lots below total`() {
        // 3분할, 1회 체결됨(filledLots=1). 다음 회차(index 1) 진입. 균등 분할 weight
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("1005"), // 익절·손절 미도달
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("10"),
                    takeProfitPct = BigDecimal("20"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.EnterSlice)
        assertEquals(1, (decision as ScaleInExitDecision.EnterSlice).sliceIndex)
    }

    @Test
    fun `scale-in ignored when slices complete`() {
        val plan = ScaleInPlan(totalSlices = 2, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000", filledLots = 2),
                    currentPrice = BigDecimal("1005"),
                    scaleIn = plan,
                    takeProfitPct = BigDecimal("20"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `price-drop slice requires sufficient drop from last fill`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                reentryTrigger = ReentryTrigger.PRICE_DROP_PCT,
                priceDropPct = BigDecimal("-3.0"),
                minSliceIntervalSec = 0,
            )
        // 직전 체결가 1000, 현재가 995 (-0.5%) → -3% 미달 → 진입 없음(HOLD)
        val notEnough =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("995"),
                    scaleIn = plan,
                    takeProfitPct = BigDecimal("20"),
                    fixedStopLossPct = BigDecimal("50"),
                    buySignalFired = true,
                    lastSliceFillPrice = BigDecimal("1000"),
                ),
            )
        assertTrue(notEnough is ScaleInExitDecision.Hold)

        // 현재가 960 (-4%) → -3% 초과 하락 → 진입
        val enough =
            notEnough.let {
                ScaleInExitCalculator.evaluate(
                    ScaleInExitInput(
                        position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                        currentPrice = BigDecimal("960"),
                        scaleIn = plan,
                        takeProfitPct = BigDecimal("20"),
                        fixedStopLossPct = BigDecimal("50"),
                        buySignalFired = true,
                        lastSliceFillPrice = BigDecimal("1000"),
                    ),
                )
            }
        assertTrue(enough is ScaleInExitDecision.EnterSlice)
    }

    @Test
    fun `min slice interval blocks too-soon reentry`() {
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 60)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("1000"),
                    scaleIn = plan,
                    takeProfitPct = BigDecimal("20"),
                    fixedStopLossPct = BigDecimal("50"),
                    buySignalFired = true,
                    lastSliceEpochSec = 1_000L,
                    nowEpochSec = 1_030L, // 30s < 60s
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `no position with buy signal and null scaleIn enters single slice`() {
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = null,
                    currentPrice = BigDecimal("1000"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.EnterSlice)
        assertEquals(0, (decision as ScaleInExitDecision.EnterSlice).sliceIndex)
    }

    @Test
    fun `hold when no buy signal and no exit condition`() {
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000"),
                    currentPrice = BigDecimal("1005"),
                    takeProfitPct = BigDecimal("20"),
                    fixedStopLossPct = BigDecimal("10"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    // ── 분할 미완 손절 보류 (2026-06-19 사용자 결재) ──────────────────────────

    @Test
    fun `scale-in incomplete defers stop-loss even when stop price reached`() {
        // 3분할, 1회차만 체결(filledLots=1<3). 평단 1000 stop 5%(=950). 현재가 940 → stop 도달이지만 분할 미완 → 보류.
        // EnterSlice 도 buySignalFired=false 라 없음 → Hold.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("940"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold) { "분할 미완(1/3)이면 손절선 도달해도 보류" }
    }

    @Test
    fun `scale-in incomplete on buy boundary enters next slice instead of stop-loss`() {
        // 분할 미완 + BUY 재발화 → 손절(보류) 대신 다음 회차 진입(라이브 09:11 의도: 물타기).
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("940"), // stop 도달
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.EnterSlice)
        assertEquals(1, (decision as ScaleInExitDecision.EnterSlice).sliceIndex)
    }

    @Test
    fun `scale-in complete fires stop-loss at final avg`() {
        // 분할 완료(filledLots=3==totalSlices). 평단 1000 stop 5%. 현재가 940 → 정상 손절(보류 해제).
        val plan = ScaleInPlan(totalSlices = 3, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 20, avgPrice = "1000", filledLots = 3),
                    currentPrice = BigDecimal("940"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
        assertEquals(20, (decision as ScaleInExitDecision.StopLoss).quantity)
    }

    @Test
    fun `⑦ scale-in incomplete at catastrophic floor emits alert only (NO auto-liquidation)`() {
        // 분할 미완(1/3) + 평단 -15% floor(=850) 도달 → 자동 청산이 아니라 **FloorBreachAlert(알람만)**.
        // 평단 1000, floor default 15% → 850. 현재가 840 ≤ 850 → 알람.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("840"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.FloorBreachAlert) { "floor 도달=알람만, 자동 청산 X (⑦)" }
        val alert = decision as ScaleInExitDecision.FloorBreachAlert
        assertEquals(8, alert.quantity)
        assertEquals(BigDecimal("1000"), alert.avgPrice)
        assertEquals(BigDecimal("840"), alert.currentPrice)
        assertEquals(BigDecimal("15"), alert.floorPct)
        // 손실률% = (840-1000)/1000 = -16% → SELL 미발행(StopLoss 아님)을 위 타입으로 보장.
        assertTrue(alert.lossPct < BigDecimal.ZERO)
    }

    @Test
    fun `scale-in incomplete does not fire when just above floor`() {
        // floor 850 직전 851 → 아직 보류(통상 물타기 구간). buySignalFired=false → Hold.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("851"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `custom floor overrides default — emits alert at custom depth`() {
        // floor 10% 명시 → 평단 1000 floor 900. 현재가 895 ≤ 900 → 분할 미완이어도 알람(자동 청산 X).
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("895"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    scaleInStopLossFloorPct = BigDecimal("10"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.FloorBreachAlert)
        assertEquals(BigDecimal("10"), (decision as ScaleInExitDecision.FloorBreachAlert).floorPct)
    }

    @Test
    fun `floor disabled (zero) means pure defer until slices complete`() {
        // floor 0(비활성) → 분할 미완이면 아무리 깊게 하락해도 손절 0(순수 보류). 평단 1000, 현재가 100(-90%) → Hold.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("100"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    scaleInStopLossFloorPct = BigDecimal.ZERO,
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold)
    }

    @Test
    fun `null scaleIn fires stop-loss as before (zero-regression)`() {
        // scaleIn 미설정(일반 전략) — filledLots 무관 현행 손절 그대로.
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("940"),
                    scaleIn = null,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
        assertEquals(10, (decision as ScaleInExitDecision.StopLoss).quantity)
    }

    @Test
    fun `single-slice scaleIn (totalSlices 1) fires stop-loss immediately`() {
        // totalSlices=1 → 첫 체결로 즉시 분할 완료(filledLots=1>=1) → 보류 없이 손절 정상.
        val plan = ScaleInPlan(totalSlices = 1, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("940"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss)
    }

    // ───────────────────────────────────────────────────────────────────────
    // ⑧ totalSlices 한도 초과 진입 차단 + ⑨ weight 0 회차 skip (2026-06-19 결재, ADR 0128)
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `⑧ enter-slice returns null when filledLots reaches effective slice count (no overflow entry)`() {
        // 3분할 완료(filledLots=3) — buySignalFired=true 여도 추가 진입 0(이전 마지막 가중 계속매수 버그 fix).
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 30, avgPrice = "1000", filledLots = 3),
                    currentPrice = BigDecimal("1000"),
                    scaleIn = plan,
                    buySignalFired = true,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold) { "분할 완료 후 BUY 재발화는 진입 0 (⑧)" }
    }

    @Test
    fun `⑨ weight-0 trailing slice — 50_50_0 completes at slice 2 (no entry for slice 3)`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                sliceWeights = listOf(BigDecimal("50"), BigDecimal("50"), BigDecimal.ZERO),
                reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
                minSliceIntervalSec = 0,
            )
        // filledLots=0 → index 0 weight 50.
        val d0 =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(position = null, currentPrice = BigDecimal("1000"), scaleIn = plan, buySignalFired = true),
            )
        assertTrue(d0 is ScaleInExitDecision.EnterSlice)
        assertEquals(0, (d0 as ScaleInExitDecision.EnterSlice).sliceIndex)
        assertEquals(BigDecimal("50"), d0.weightPct)

        // filledLots=1 → index 1 weight 50.
        val d1 =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("1000"),
                    scaleIn = plan,
                    buySignalFired = true,
                ),
            )
        assertTrue(d1 is ScaleInExitDecision.EnterSlice)
        assertEquals(1, (d1 as ScaleInExitDecision.EnterSlice).sliceIndex)

        // filledLots=2 → 실효 회차수 2 도달 → 진입 0(0% 회차 skip = totalSlices 2 와 동일).
        val d2 =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 10, avgPrice = "1000", filledLots = 2),
                    currentPrice = BigDecimal("1000"),
                    scaleIn = plan,
                    buySignalFired = true,
                ),
            )
        assertTrue(d2 is ScaleInExitDecision.Hold) { "0% 회차는 skip — 진입 0 (⑨)" }
    }

    @Test
    fun `⑨ weight-0 middle slice — 50_0_50 maps filledLots 1 to slice index 2`() {
        val plan =
            ScaleInPlan(
                totalSlices = 3,
                sliceWeights = listOf(BigDecimal("50"), BigDecimal.ZERO, BigDecimal("50")),
                reentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
                minSliceIntervalSec = 0,
            )
        // filledLots=1 → 중간 0% index 1 건너뛰고 index 2(50%) 진입.
        val d =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 5, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("1000"),
                    scaleIn = plan,
                    buySignalFired = true,
                ),
            )
        assertTrue(d is ScaleInExitDecision.EnterSlice)
        assertEquals(2, (d as ScaleInExitDecision.EnterSlice).sliceIndex)
        assertEquals(BigDecimal("50"), d.weightPct)
    }

    @Test
    fun `⑦ floor alert never sells — stop-loss completion still sells (boundary)`() {
        // 동일 가격에서 분할 완료면 StopLoss, 미완이면 FloorBreachAlert — 청산 여부가 갈린다.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val incomplete =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("800"), // -20% (floor 15% 초과)
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                ),
            )
        val complete =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 24, avgPrice = "1000", filledLots = 3),
                    currentPrice = BigDecimal("800"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                ),
            )
        assertTrue(incomplete is ScaleInExitDecision.FloorBreachAlert) { "분할 미완 → 알람만" }
        assertTrue(complete is ScaleInExitDecision.StopLoss) { "분할 완료 → 손절 청산" }
    }

    // ───────────────────────────────────────────────────────────────────────
    // 갭② floorAutoLiquidate 토글 (ADR 0128 amend) — default false 알람만(회귀) ↔ true 자동 청산
    // ───────────────────────────────────────────────────────────────────────

    @Test
    fun `갭② floorAutoLiquidate=false at floor emits alert only (zero-regression default)`() {
        // default false — 분할 미완 + floor(평단 -15%=850) 도달 → FloorBreachAlert(현행 ⑦ 유지).
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("840"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    floorAutoLiquidate = false,
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.FloorBreachAlert) { "floorAutoLiquidate=false → 알람만(회귀)" }
        assertEquals(8, (decision as ScaleInExitDecision.FloorBreachAlert).quantity)
    }

    @Test
    fun `갭② floorAutoLiquidate=true at floor auto-liquidates (StopLoss)`() {
        // true(opt-in) — 분할 미완 + floor(850) 도달 → FloorBreachAlert 가 아니라 StopLoss(자동 전량 청산).
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("840"), // -16% (floor 15% 초과)
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    floorAutoLiquidate = true,
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss) { "floorAutoLiquidate=true → 자동 청산(StopLoss)" }
        assertEquals(8, (decision as ScaleInExitDecision.StopLoss).quantity)
    }

    @Test
    fun `갭② floorAutoLiquidate=true above floor still defers (no liquidation until floor reached)`() {
        // true 여도 floor(850) 미도달(851)이면 청산 X — 분할 미완 보류(⑤). 토글은 floor 도달 시에만 작동.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("851"), // floor 직전 — 통상 물타기 구간
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    floorAutoLiquidate = true,
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold) { "floor 미도달이면 토글 true 여도 보류(청산 X)" }
    }

    @Test
    fun `갭② floorAutoLiquidate=true but floor disabled (zero) means pure defer`() {
        // floor 0(비활성) → 토글 true 여도 청산·알람 모두 없음(순수 보류). 평단 1000, 현재가 100(-90%) → Hold.
        val plan = ScaleInPlan(totalSlices = 3, reentryTrigger = ReentryTrigger.SIGNAL_REPEAT, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 8, avgPrice = "1000", filledLots = 1),
                    currentPrice = BigDecimal("100"),
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    scaleInStopLossFloorPct = BigDecimal.ZERO,
                    floorAutoLiquidate = true,
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.Hold) { "floor 비활성이면 토글 true 여도 청산·알람 0" }
    }

    @Test
    fun `갭② floorAutoLiquidate=true does not affect complete-slice stop-loss (FIRE unchanged)`() {
        // 분할 완료면 floorAutoLiquidate 와 무관하게 평단 -stopLossPct% 에서 정상 손절(FIRE 불변).
        val plan = ScaleInPlan(totalSlices = 3, minSliceIntervalSec = 0)
        val decision =
            ScaleInExitCalculator.evaluate(
                ScaleInExitInput(
                    position = position(quantity = 20, avgPrice = "1000", filledLots = 3),
                    currentPrice = BigDecimal("940"), // -6% (stop 5% 도달, floor 미도달)
                    scaleIn = plan,
                    fixedStopLossPct = BigDecimal("5"),
                    floorAutoLiquidate = true,
                    buySignalFired = false,
                ),
            )
        assertTrue(decision is ScaleInExitDecision.StopLoss) { "분할 완료 손절은 토글 무관 FIRE" }
        assertEquals(20, (decision as ScaleInExitDecision.StopLoss).quantity)
    }
}

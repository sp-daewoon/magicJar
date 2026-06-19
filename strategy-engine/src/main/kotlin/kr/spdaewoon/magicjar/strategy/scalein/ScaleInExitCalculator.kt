package kr.spdaewoon.magicjar.strategy.scalein

import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 분할 매수(scale-in) + 평단 기준 청산(exit) 단일 평가 순수 함수 — ADR 0111 §2-4 · spec §6-3.
 *
 * scale-in 회차 진입 + 평단 기준 stop-loss/take-profit/분할 익절 평가를 **하나의 순수 함수**로 묶는다.
 * live(RealtimeSignalGenerator)·리플레이(ScenarioLedger, R3)·백테스트(ADR 0056, R3)가
 * **이 단일 함수**를 호출 → "리플레이에선 분할 익절됐는데 라이브는 안 됨" 의심 원천 차단(readiness P1 정합).
 *
 * ## 순수성 (LOCK)
 * - Spring·JPA·Jackson·Clock·IO 의존 0. 입력([ScaleInExitInput]) → 출력([ScaleInExitDecision]) 결정론.
 * - 현재 시각·직전 체결가 등 외부 상태는 **입력으로 주입**한다(호출자 책임). 함수 내부에서 조회하지 않는다.
 * - 동일 입력 → 동일 출력(결정론 우선 — 백테스트 재현성).
 *
 * ## 평가 규칙 (우선순위 LOCK — spec §3-3 · §5-2 · ADR 0111 §2-3)
 *
 * 청산(exit)을 **진입(scale-in)보다 먼저** 평가하고, 청산 내부 우선순위는:
 *
 * 1. **STOP_LOSS** (최우선 — 보수적). 현재가 ≤ 평단 × (1 - stopLossPct/100) → 전량 SELL.
 *    동시 터치(stop·take 동시 조건) 시 손절 우선(ADR 0107 §2-6).
 *
 *    ### ★ 분할 미완 손절 보류 (2026-06-19 사용자 결재 — 자본 경로 critical 변경)
 *    분할매수(scale-in)는 "하락 시 물타기로 평단을 낮추는" 전략이다. 1회차(filledLots=1/3)에서 평단×0.95
 *    손절선에 닿았다고 즉시 청산하면 2·3회차 물타기 기회가 사라져 분할의 의미가 무력화된다(라이브 실증 2026-06-19
 *    09:11 — 전략 47 3분할[40,35,25] 1회차 8주@18830 갭하락 17850→손절선 17880 도달→청산, 분할 미완).
 *    따라서 **scaleIn 설정 + `filledLots < totalSlices`(분할 미완)** 동안에는 평단 기준 손절을 **보류**한다.
 *    분할 완료(`filledLots >= totalSlices`) 후 최종 평단 -stopLossPct%에서 비로소 손절이 정상 발동한다(현행).
 *
 *    ### ★★ 무한 물타기 교착 방지 — catastrophic floor ([ScaleInExitInput.scaleInStopLossFloorPct])
 *    손절 보류가 **무한**이 되면 "분할도 안 되고(SIGNAL_REPEAT 인데 BUY 재발화 없음) 손절도 안 되는" 교착이 생긴다.
 *    단일 평가에서 "EnterSlice가 앞으로 영영 불가능"은 결정론적으로 증명할 수 없다(미래 BUY 시그널·가격 미지).
 *    그래서 보류 자체에 **상한**을 둔다: 평단 대비 손실이 [scaleInStopLossFloorPct]%(default
 *    [DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT]) 이상으로 깊어지면 분할 미완이라도 손절을 발동한다(catastrophic exit).
 *    이 floor 는 통상 stopLossPct 보다 **충분히 깊게**(예: 평단 -15%) 잡아, 정상 물타기 구간(-5%)에선 절대 닿지
 *    않고 진짜 붕괴(급락 지속·분할 진척 0)에서만 발동한다. 이로써 손실 상한 = max(분할 전량 매수액 평가손,
 *    floor 도달 청산) 로 명확히 bound 된다(worst-case 분석은 PR 본문).
 * 2. **PARTIAL_TP** — `partialExits`의 미발화 단계 중 trigger 도달한 **가장 높은 trigger** 1단계 청산.
 *    `stageIndex !in executedExitStages` AND `profitPct >= triggerProfitPct`. 보유 수량 × weight% SELL.
 * 3. **TAKE_PROFIT** — profitPct >= takeProfitPct → 잔량 전량 SELL.
 * 4. (포지션 보유 시 청산 조건 미충족) → 진입 평가로 진행하지 않음([ScaleInExitDecision.Hold]).
 *
 * 진입(ENTER_SLICE)은 **BUY 시그널이 발화한 boundary**(`buySignalFired=true`)에서만 평가한다(spec §5-1):
 *
 * 5. **ENTER_SLICE** — scale-in 설정 + `filledLots < totalSlices` + ReentryTrigger 충족 + 회차 간격 충족.
 *    회차 weight 기반 수량은 호출자가 budget으로 산정([ScaleInExitInput.sliceBudgetQuantity]);
 *    본 함수는 ENTER_SLICE 회차 인덱스·weight·trigger를 반환.
 *
 * ## 하위호환 (zero-regression — spec §3-2 · §3-3)
 * - `scaleIn == null` → 진입은 현행 단일 진입(미보유 + BUY 시그널일 때만 ENTER_SLICE 회차 0).
 * - `partialExits == null` → 분할 익절 미평가(PARTIAL_TP 절대 미발생).
 * - 본 함수는 scale-in/partial-exit 활성 전략에 의미가 있으며, 호출자가 toggle/null 가드 후 호출한다(spec §8-1).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 순수 함수 (ADR 0023 §2-4 정합)
 */
object ScaleInExitCalculator {
    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val HUNDRED: BigDecimal = BigDecimal("100")

    /**
     * 분할 미완 손절 보류의 catastrophic floor default(%, 양수 = 평단 대비 손실폭).
     *
     * 분할 미완(filledLots<totalSlices) 동안 평단 기준 손절을 보류하되, 평단 대비 손실이 이 값 이상으로
     * 깊어지면 분할 미완이라도 손절을 발동한다(교착·무한 보류 방지). 통상 fixedStopLossPct(예 5)의 약 3배로
     * 잡아, 정상 물타기 구간에선 절대 닿지 않고 진짜 붕괴에서만 발동한다. 호출자가 명시하지 않으면 이 default.
     * 라이브 실증(전략 47)은 stopLossPct=5% 이므로 floor 15% = 평단 -15%(급락) 시 분할 중에도 청산.
     */
    val DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT: BigDecimal = BigDecimal("15")

    /**
     * 단일 평가 진입점. exit(청산) 우선 → 진입(scale-in) 순으로 평가하고 단일 액션을 반환한다.
     */
    fun evaluate(input: ScaleInExitInput): ScaleInExitDecision {
        val position = input.position
        val quantity = position?.quantity ?: 0

        // === 1. 청산(exit) 평가 — 보유분이 있을 때만 ===
        if (position != null && quantity > 0 && position.avgPrice > ZERO) {
            // 1-a. STOP_LOSS (최우선 — 보수적, 동시 터치 시 손절 우선)
            input.fixedStopLossPct?.let { stopPct ->
                val stopPrice = stopLossPrice(position.avgPrice, stopPct)
                if (input.currentPrice <= stopPrice && shouldFireStopLoss(input, position)) {
                    return ScaleInExitDecision.StopLoss(quantity = quantity)
                }
            }

            val profitPct = profitPct(position.avgPrice, input.currentPrice)

            // 1-b. PARTIAL_TP — 미발화 단계 중 trigger 도달한 가장 높은 trigger 1단계
            val partial = resolvePartialExit(input.partialExits, position.executedExitStages, profitPct)
            if (partial != null) {
                val (stageIndex, exit) = partial
                val sellQty = exitQuantity(quantity, exit.exitWeightPct)
                if (sellQty > 0) {
                    return ScaleInExitDecision.PartialTakeProfit(
                        stageIndex = stageIndex,
                        weightPct = exit.exitWeightPct,
                        quantity = sellQty,
                        profitPct = profitPct,
                    )
                }
            }

            // 1-c. TAKE_PROFIT — 잔량 전량
            input.takeProfitPct?.let { takePct ->
                if (profitPct >= takePct) {
                    return ScaleInExitDecision.TakeProfit(quantity = quantity, profitPct = profitPct)
                }
            }
        }

        // === 2. 진입(scale-in) 평가 — BUY 시그널 발화 boundary에서만 ===
        if (input.buySignalFired) {
            val enter = resolveScaleInSlice(input)
            if (enter != null) return enter
        }

        return ScaleInExitDecision.Hold
    }

    /** 평단 대비 수익률%(currentPrice 기준). avgPrice>0 전제(호출부 가드). */
    private fun profitPct(
        avgPrice: BigDecimal,
        currentPrice: BigDecimal,
    ): BigDecimal =
        currentPrice
            .subtract(avgPrice)
            .divide(avgPrice, 6, RoundingMode.HALF_UP)
            .multiply(HUNDRED)

    /** 손절 발화가 = 평단 × (1 - stopPct/100). */
    private fun stopLossPrice(
        avgPrice: BigDecimal,
        stopPct: BigDecimal,
    ): BigDecimal =
        avgPrice.multiply(
            BigDecimal.ONE.subtract(stopPct.divide(HUNDRED, 8, RoundingMode.HALF_UP)),
        )

    /**
     * 평단 손절선 도달 시 실제로 손절을 발동할지 판정 — 분할 미완 보류 + catastrophic floor (2026-06-19 사용자 결재).
     *
     * 반환 규칙(순수·결정론):
     * - **scaleIn 미설정(null)** → 항상 true(현행 손절 그대로 — zero-regression).
     * - **분할 완료(filledLots >= totalSlices)** → true(최종 평단 -stopLossPct%에서 손절 정상).
     * - **분할 미완(filledLots < totalSlices)** → 평단 기준 손절을 보류(false). 단, 평단 대비 손실이 catastrophic
     *   floor([ScaleInExitInput.scaleInStopLossFloorPct], default [DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT]) 이상이면
     *   true(무한 보류·교착 방지 — 진짜 붕괴 청산).
     *
     * ## 교착(deadlock) 회피 근거 (사용자 정밀검토 요청 응답)
     * "분할도 안 되고(SIGNAL_REPEAT 인데 BUY 재발화 없음) 손절도 안 되는" 교착은, 단일 평가에서 "EnterSlice가
     * 앞으로 영영 불가능"을 결정론적으로 증명할 수 없으므로 *직접* 감지하지 않는다(미래 BUY/가격 미지).
     * 대신 보류 자체에 **손실폭 상한(floor)**을 둬 모든 경로에서 손실이 bound 되게 한다:
     *   ① PRICE_DROP_PCT — 하락이 지속되면 추가 회차가 빠르게 채워져 곧 filledLots>=totalSlices(보류 자연 종료) → 손절 정상.
     *   ② SIGNAL_REPEAT — BUY 재발화가 끊겨 분할이 멈춰도, 급락이 floor(평단 -15%)에 닿으면 청산되어 손실 상한 보장.
     * 즉 "EnterSlice 불가능을 푸는 조건" = "손실이 floor에 도달" 로 환원한다(코드 근거 결정).
     */
    private fun shouldFireStopLoss(
        input: ScaleInExitInput,
        position: StrategyPosition,
    ): Boolean {
        val scaleIn = input.scaleIn ?: return true // scale-in 미설정 — 현행 손절(zero-regression)
        if (position.filledLots >= scaleIn.totalSlices) return true // 분할 완료 — 최종 평단 손절 정상

        // 분할 미완 — 평단 기준 손절 보류. catastrophic floor 도달 시에만 발동(교착 방지).
        val floorPct = input.scaleInStopLossFloorPct ?: DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT
        if (floorPct <= ZERO) return false // floor 비활성(≤0) — 순수 보류(분할 완료까지 손절 0)
        val floorPrice = stopLossPrice(position.avgPrice, floorPct) // 평단 × (1 - floorPct/100)
        return input.currentPrice <= floorPrice
    }

    /** 청산 수량 = floor(보유수량 × weight% / 100). 0..quantity로 clamp. */
    private fun exitQuantity(
        quantity: Int,
        weightPct: BigDecimal,
    ): Int =
        BigDecimal(quantity)
            .multiply(weightPct)
            .divide(HUNDRED, 0, RoundingMode.DOWN)
            .toInt()
            .coerceIn(0, quantity)

    /**
     * 미발화 분할 익절 단계 중 trigger 도달한 **가장 높은 triggerProfitPct** 1단계를 고른다.
     * 한 boundary에서 여러 단계가 동시 도달해도 가장 높은 단계 1건만 반환(나머지는 다음 boundary).
     * 동률 trigger는 더 작은 stageIndex 우선(단조 진행 보존). 미발화 단계는 executedExitStages로 차단.
     */
    private fun resolvePartialExit(
        partialExits: List<PartialExit>?,
        executedExitStages: Set<Int>,
        profitPct: BigDecimal,
    ): Pair<Int, PartialExit>? {
        if (partialExits.isNullOrEmpty()) return null
        return partialExits
            .withIndex()
            .filter { (idx, exit) -> idx !in executedExitStages && profitPct >= exit.triggerProfitPct }
            .maxWithOrNull(
                compareBy<IndexedValue<PartialExit>>({ it.value.triggerProfitPct }).thenByDescending { it.index },
            )?.let { it.index to it.value }
    }

    /**
     * scale-in 추가 회차 진입 가부를 평가한다(BUY 시그널 발화 boundary 전제).
     *
     * - `scaleIn == null` → 미보유 + BUY 시그널이면 ENTER_SLICE(회차 0, weight 100) — 단일 진입 호환.
     *   이미 보유 중이면 추가 진입 X(현행 동작 보존).
     * - `filledLots >= totalSlices` → null(scale-in 완료 — 평단 오염 방지).
     * - ReentryTrigger·회차 간격 충족 시 ENTER_SLICE(다음 회차 인덱스·weight·trigger).
     */
    private fun resolveScaleInSlice(input: ScaleInExitInput): ScaleInExitDecision.EnterSlice? {
        val scaleIn = input.scaleIn
        val filledLots = input.position?.filledLots ?: 0
        val heldQty = input.position?.quantity ?: 0

        if (scaleIn == null) {
            // 단일 진입 호환 — 보유 0 + BUY 시그널이면 1회 진입. 이미 보유 중이면 추가 진입 X(현행 동작 보존).
            return if (filledLots == 0 && heldQty == 0) {
                ScaleInExitDecision.EnterSlice(
                    sliceIndex = 0,
                    weightPct = HUNDRED,
                    trigger = ReentryTrigger.SIGNAL_REPEAT,
                    sliceBudgetQuantity = input.sliceBudgetQuantity,
                )
            } else {
                null
            }
        }

        if (filledLots >= scaleIn.totalSlices) return null

        // 회차 간 최소 간격 가드 (첫 회차 제외)
        val minIntervalSec = scaleIn.minSliceIntervalSec ?: 0
        if (filledLots > 0 && minIntervalSec > 0) {
            val last = input.lastSliceEpochSec
            val now = input.nowEpochSec
            if (last != null && now != null && (now - last) < minIntervalSec) {
                return null
            }
        }

        // ReentryTrigger 평가
        when (scaleIn.reentryTrigger) {
            ReentryTrigger.SIGNAL_REPEAT -> {
                // BUY 시그널 재발화 자체가 트리거 (buySignalFired=true 전제) — 추가 조건 없음
            }
            ReentryTrigger.PRICE_DROP_PCT -> {
                if (filledLots > 0) {
                    // 첫 회차(filledLots==0)는 가격 하락 조건 없이 진입. 추가 회차만 직전 체결가 대비 하락 확인.
                    val lastFill = input.lastSliceFillPrice ?: return null
                    val drop = scaleIn.priceDropPct ?: return null // 음수
                    if (lastFill <= ZERO) return null
                    val changePct = profitPct(lastFill, input.currentPrice) // 직전 체결가 대비 변화율%
                    if (changePct > drop) return null // 충분히 하락하지 않음(drop은 음수)
                }
            }
        }

        val weights = scaleIn.resolvedWeights()
        val sliceIndex = filledLots
        val weight = weights.getOrElse(sliceIndex) { weights.lastOrNull() ?: HUNDRED }
        return ScaleInExitDecision.EnterSlice(
            sliceIndex = sliceIndex,
            weightPct = weight,
            trigger = scaleIn.reentryTrigger,
            sliceBudgetQuantity = input.sliceBudgetQuantity,
        )
    }
}

/**
 * [ScaleInExitCalculator.evaluate] 입력 — 모든 외부 상태를 명시 주입(순수성 LOCK).
 *
 * @param position 그 전략의 현재 포지션(없으면 null = 미보유). 평단·filledLots·executedExitStages 권위.
 * @param currentPrice 현재가(boundary close 또는 tick).
 * @param scaleIn 분할 매수 정책(null = 단일 진입).
 * @param fixedStopLossPct 평단 대비 손절% (null = stop-loss exit 미평가).
 * @param takeProfitPct 평단 대비 전량 익절% (null = 전량 익절 미평가).
 * @param partialExits 분할 익절 단계(null/empty = 분할 익절 미평가).
 * @param buySignalFired 이 boundary에서 BUY 시그널이 발화했는지(진입 평가 게이트 — spec §5-1).
 * @param sliceBudgetQuantity 회차 수량(호출자가 종목예산×weight%/price로 산정 — R2-2 어댑터 전엔 0 가능). ENTER_SLICE에 전달.
 * @param lastSliceFillPrice 직전 회차 체결가(PRICE_DROP_PCT 평가용). 없으면 null(추가 회차 평가 보류).
 * @param lastSliceEpochSec 직전 회차 체결 epoch 초(minSliceInterval 평가용). 없으면 null(간격 가드 skip).
 * @param nowEpochSec 현재 epoch 초(minSliceInterval 평가용 — 호출자 주입). 없으면 null.
 * @param scaleInStopLossFloorPct 분할 미완 손절 보류의 catastrophic floor%(양수). null이면
 *   [ScaleInExitCalculator.DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT]. ≤0이면 floor 비활성(분할 완료까지 순수 보류).
 *   scaleIn 미설정 전략에는 무의미(보류 자체가 없음).
 */
data class ScaleInExitInput(
    val position: StrategyPosition?,
    val currentPrice: BigDecimal,
    val scaleIn: ScaleInPlan? = null,
    val fixedStopLossPct: BigDecimal? = null,
    val takeProfitPct: BigDecimal? = null,
    val partialExits: List<PartialExit>? = null,
    val buySignalFired: Boolean = false,
    val sliceBudgetQuantity: Int = 0,
    val lastSliceFillPrice: BigDecimal? = null,
    val lastSliceEpochSec: Long? = null,
    val nowEpochSec: Long? = null,
    val scaleInStopLossFloorPct: BigDecimal? = null,
)

/**
 * [ScaleInExitCalculator.evaluate] 출력 — 다음 단일 액션(ADR 0111 §2-4 sealed).
 *
 * 각 액션은 자본을 직접 움직이지 않는다 — 호출자(RealtimeSignalGenerator)가 **시그널을 발행**하고
 * 기존 AUTO 경로(시그널 → OrderIntent)를 통과한다(spec §5-3 LOCK — OrderIntent 직접 생성 금지).
 */
sealed interface ScaleInExitDecision {
    /** 추가 회차 진입 — BUY 시그널(reason `SCALE_IN_SLICE#{N}:{trigger}`). */
    data class EnterSlice(
        /** 0-기반 회차 인덱스(= 진입 전 filledLots). reason 표기는 `#{sliceIndex+1}`. */
        val sliceIndex: Int,
        /** 이 회차 비중%(전략 종목 예산 분배 base). */
        val weightPct: BigDecimal,
        val trigger: ReentryTrigger,
        /** 호출자가 산정한 회차 수량(0이면 호출자가 budget 미산정 — R2-2 어댑터 전). */
        val sliceBudgetQuantity: Int,
    ) : ScaleInExitDecision

    /** 손절 청산 — 전량 SELL(reason `STOP_LOSS`). */
    data class StopLoss(
        val quantity: Int,
    ) : ScaleInExitDecision

    /** 분할 익절 — 보유분 weight% SELL(reason `PARTIAL_TAKE_PROFIT#{stageIndex}`). */
    data class PartialTakeProfit(
        val stageIndex: Int,
        val weightPct: BigDecimal,
        val quantity: Int,
        val profitPct: BigDecimal,
    ) : ScaleInExitDecision

    /** 전량 익절 — 잔량 전량 SELL(reason `TAKE_PROFIT`). */
    data class TakeProfit(
        val quantity: Int,
        val profitPct: BigDecimal,
    ) : ScaleInExitDecision

    /** 아무 액션 없음. */
    data object Hold : ScaleInExitDecision
}

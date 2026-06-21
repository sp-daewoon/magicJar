package kr.spdaewoon.magicjar.strategy.scalein

import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.strategy.PartialExit
import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInLimits
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
 *    ### ★★ catastrophic floor = **알람만(default)** ↔ **자동 청산(opt-in)** (ADR 0128 ⑦ + amend 갭②)
 *    이전(#1058)엔 분할 미완 중 평단 대비 손실이 floor([ScaleInExitInput.scaleInStopLossFloorPct]) 이상으로
 *    깊어지면 **자동 청산**(StopLoss)했다. 2026-06-19 사용자 결재 ⑦로 이를 **[FloorBreachAlert](알람만)로 강등**해
 *    floor 도달은 Slack 알람만 발행하고 **주문 연동 0**이 되었다(스윙 변동성에서 기계적 청산이 반등을 놓치는 위험 회피).
 *    **이 알람-only 가 default 다(zero-regression).**
 *
 *    ADR 0128 amend 갭②로 전략별 **[ScaleInExitInput.floorAutoLiquidate] 토글**을 추가한다:
 *    - `false`(default) → floor 도달 시 [FloorBreachAlert](알람만, 자동 청산 X — ⑦ 유지).
 *    - `true`(명시 opt-in) → floor 도달 시 [ScaleInExitDecision.StopLoss](자동 전량 청산 — 기존 손절 경로 재사용).
 *      사용자가 floor 기계적 청산의 반등-놓침 위험을 감수하고 켠 전략에 한해 floor 가 손절선으로 복귀한다.
 *
 *    어느 경우든 floor 가 ≤0 이면 floor 비활성(알람·청산 모두 없는 순수 보류). 저장 시
 *    `floor < 분할 최저 진입가`([ScaleInLimits.validateFloorBelowDeepestEntry]) 검증으로 floor 가 분할을 막지 않게 보장한다.
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
     * floor null fallback 단일 출처 — **domain [ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT] 위임**(ADR 0128 amend 갭①).
     *
     * 이전엔 strategy-engine 측에 별도 `DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT = BigDecimal("15")` 중복 상수가 있었으나
     * (값 drift 위험) 제거하고 floor 단일 출처를 domain 상수로 통일했다. 엔진은 호출자가 floor 를 명시하지 않을 때
     * (`scaleInStopLossFloorPct == null`) last-resort 안전망으로 이 값을 쓴다. 운영 default floor 는 yml(api 주입)이며
     * 이 상수가 아니다(domain KDoc 참조). consumer 1m 안전망 경로([ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT]를 직접
     * 참조)와 동일 값 정합.
     */
    @Deprecated(
        "중복 상수 제거 — floor 단일 출처는 domain ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT (ADR 0128 amend 갭①).",
        ReplaceWith(
            "ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT",
            "kr.spdaewoon.magicjar.domain.strategy.ScaleInLimits",
        ),
    )
    val DEFAULT_SCALE_IN_STOP_LOSS_FLOOR_PCT: BigDecimal
        get() = ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT

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
                if (input.currentPrice <= stopPrice) {
                    when (stopLossVerdict(input, position)) {
                        // 손절 발동 — 평단 -stopLossPct% 도달 + (분할 미설정 OR 분할 완료).
                        StopLossVerdict.FIRE -> return ScaleInExitDecision.StopLoss(quantity = quantity)
                        // 분할 미완 + catastrophic floor 도달 — floorAutoLiquidate 토글로 분기 (ADR 0128 ⑦ + amend 갭②).
                        StopLossVerdict.FLOOR_ALERT -> {
                            // 갭② opt-in: 자동 전량 청산(기존 손절 경로 재사용). default false → 알람만(⑦ 유지).
                            if (input.floorAutoLiquidate) {
                                return ScaleInExitDecision.StopLoss(quantity = quantity)
                            }
                            val floorPct = input.scaleInStopLossFloorPct ?: ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT
                            return ScaleInExitDecision.FloorBreachAlert(
                                quantity = quantity,
                                avgPrice = position.avgPrice,
                                currentPrice = input.currentPrice,
                                floorPct = floorPct,
                                lossPct = profitPct(position.avgPrice, input.currentPrice),
                            )
                        }
                        // 분할 미완 + floor 미도달 → 손절 보류(다른 청산/진입 평가로 진행).
                        StopLossVerdict.DEFER -> Unit
                    }
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

    /** 평단 손절선 도달 시 처리 판정 — 손절 발동 / floor 알람 / 보류 3분기 (2026-06-19 사용자 결재 ⑤⑥⑦). */
    private enum class StopLossVerdict {
        /** 평단 -stopLossPct% 손절 발동(전량 SELL). 분할 미설정 또는 분할 완료. */
        FIRE,

        /** 분할 미완 + catastrophic floor 도달 — 알람만(⑦) 또는 자동 청산(floorAutoLiquidate=true, 갭②). [evaluate]가 분기. */
        FLOOR_ALERT,

        /** 분할 미완 + floor 미도달 — 손절 보류(⑤). */
        DEFER,
    }

    /**
     * 평단 손절선 도달 시 처리 판정 — 분할 미완 보류(⑤) + 분할 완료 손절(⑥) + catastrophic floor **알람 강등**(⑦).
     *
     * 반환 규칙(순수·결정론):
     * - **scaleIn 미설정(null)** → [StopLossVerdict.FIRE](현행 손절 그대로 — zero-regression).
     * - **분할 완료(filledLots >= effectiveSliceCount)** → FIRE(최종 평단 -stopLossPct%에서 손절 정상 ⑥).
     * - **분할 미완(filledLots < effectiveSliceCount)**:
     *     - 평단 대비 손실이 floor 이상으로 깊으면 → [StopLossVerdict.FLOOR_ALERT](알람만 ⑦ / 토글 시 청산 갭② — [evaluate] 분기).
     *     - 그 외 → [StopLossVerdict.DEFER](손절 보류 ⑤).
     *   floor([ScaleInExitInput.scaleInStopLossFloorPct], default [ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT]) ≤0 이면
     *   FLOOR_ALERT 도 없는 순수 보류(항상 DEFER) — 분할 완료까지 손절·알람·청산 0.
     *
     * ## 분할 완료 판정 = `effectiveSliceCount`(⑨ 정합)
     * totalSlices 가 아니라 **양수 비중 회차 수**([ScaleInPlan.effectiveSliceCount])를 쓴다. [50,50,0] 처럼 0% 회차가
     * 있으면 그 회차는 체결되지 않아 filledLots 가 totalSlices 에 영영 못 닿는다(분할 완료 판정 불가 → 손절 영구 보류).
     * 실효 회차 수로 판정하면 0% 회차 제외 후 정확히 완료된다.
     */
    private fun stopLossVerdict(
        input: ScaleInExitInput,
        position: StrategyPosition,
    ): StopLossVerdict {
        val scaleIn = input.scaleIn ?: return StopLossVerdict.FIRE // scale-in 미설정 — 현행 손절(zero-regression)
        if (position.filledLots >= scaleIn.effectiveSliceCount()) return StopLossVerdict.FIRE // 분할 완료 — 손절 정상 ⑥

        // 분할 미완 — 평단 기준 손절 보류 ⑤. catastrophic floor 도달 시 알람만 ⑦(또는 floorAutoLiquidate=true 면 청산 갭②).
        val floorPct = input.scaleInStopLossFloorPct ?: ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT
        if (floorPct <= ZERO) return StopLossVerdict.DEFER // floor 비활성 — 순수 보류(알람도 없음)
        val floorPrice = stopLossPrice(position.avgPrice, floorPct) // 평단 × (1 - floorPct/100)
        return if (input.currentPrice <= floorPrice) StopLossVerdict.FLOOR_ALERT else StopLossVerdict.DEFER
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
     * - `filledLots >= effectiveSliceCount` → null(scale-in 완료 — 평단 오염 방지 ⑧ 초과 진입 차단).
     * - ReentryTrigger·회차 간격 충족 시 ENTER_SLICE(다음 회차 인덱스·weight·trigger).
     *
     * ### ⑧ totalSlices 한도 초과 진입 차단 (2026-06-19 라이브 버그 fix)
     * 이전엔 `weights.getOrElse(filledLots){weights.last()}` 로 filledLots 가 totalSlices 이상이어도 마지막 가중으로
     * 계속 진입했다(전략47 4회차 sliceIndex=3 초과 실증). 분할 완료([ScaleInPlan.sliceAt] null)면 EnterSlice 를 내지
     * 않아 초과 진입을 0으로 만든다.
     *
     * ### ⑨ weight 0% 회차 skip (2026-06-19 결재)
     * [ScaleInPlan.sliceAt]가 0% 회차를 건너뛰어 filledLots 번째 **양수 비중** 회차의 (index, weight)만 반환한다.
     * [50,50,0] 은 실효 2회차에서 sliceAt(2)=null → 진입 종료(totalSlices=2 [50,50]와 동일 결과).
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

        // ⑧⑨ — filledLots 번째 양수 비중 회차의 (index, weight). 완료/모두 0%면 null → 진입 0.
        val slice = scaleIn.sliceAt(filledLots) ?: return null

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

        val (sliceIndex, weight) = slice
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
 * @param scaleInStopLossFloorPct 분할 미완 손절 보류의 catastrophic floor%(양수). null이면 last-resort 안전망
 *   [kr.spdaewoon.magicjar.domain.strategy.ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT]. ≤0이면 floor 비활성
 *   (분할 완료까지 순수 보류). scaleIn 미설정 전략에는 무의미(보류 자체가 없음).
 * @param floorAutoLiquidate catastrophic floor 도달 시 자동 전량 청산 여부 (ADR 0128 amend 갭②). default false = 알람만
 *   ([ScaleInExitDecision.FloorBreachAlert], ⑦ 유지 — zero-regression). true면 floor 도달 시 [ScaleInExitDecision.StopLoss]
 *   (자동 청산). 호출자([ScaleInPlan.floorAutoLiquidate])가 주입. floor 비활성(≤0)이면 토글과 무관하게 청산·알람 모두 없음.
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
    val floorAutoLiquidate: Boolean = false,
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

    /**
     * **catastrophic floor 도달 알람** — 분할 미완 중 평단 대비 손실이 floor 이상으로 깊어졌다 (2026-06-19 결재 ⑦).
     *
     * **주문 연동 0** — SELL 을 발행하지 않는다. 호출자(RealtimeSignalGenerator)는 이 결정을 받으면 Slack 알람만
     * 송출하고 매매 경로로 흘리지 않는다. 청산 여부는 사용자 수동 판단. 같은 알람의 반복 폭주는 호출자가 idempotency
     * (예: 포지션·floor 단위 1회/일)로 제어한다(엔진은 결정론적으로 매 boundary 마다 동일 결정을 반환).
     */
    data class FloorBreachAlert(
        /** 현재 보유 수량(알람 메시지용). */
        val quantity: Int,
        /** 평단. */
        val avgPrice: BigDecimal,
        /** floor 도달 시점 현재가. */
        val currentPrice: BigDecimal,
        /** 적용된 floor%(양수, 평단 대비 손실폭). */
        val floorPct: BigDecimal,
        /** 평단 대비 현재 손실률%(음수). */
        val lossPct: BigDecimal,
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

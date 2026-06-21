package kr.spdaewoon.magicjar.strategy.scalein

import kr.spdaewoon.magicjar.domain.strategy.ReentryTrigger
import kr.spdaewoon.magicjar.domain.strategy.ScaleInPlan
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 분할 매수 **계획 투영기**(frontend UI용 read-only projection) — 2026-06-19 사용자 결재 ⑧·⑨, ADR 0128.
 *
 * 1차가 정해지면(또는 진입 전 미리 보기) 회차별 "목표 수량 + 진입 조건 + 예상 손절가 + floor가"를 산출한다.
 * **순수 함수** — Spring·JPA·IO 의존 0. api 컨트롤러(또는 frontend BFF)가 이 결과를 DTO 로 직렬화해 화면에 노출한다.
 *
 * ## 계약 (PR 본문 명시 — frontend 계약 LOCK)
 * - `slices`: **0% 회차는 제외**한다(⑨ — [ScaleInPlan.effectiveSliceCount] 개수). 각 항목은:
 *     - `sliceIndex`        : 0-기반 원본 회차 인덱스(reason 표기 `#{sliceIndex+1}`와 정합). 0% skip 시 비연속일 수 있음.
 *     - `displayOrder`      : 화면 표시 순번(1-기반, 0% 제외 후 1·2·3…).
 *     - `weightPct`         : 회차 비중%(고정 base 분배 기준).
 *     - `targetQuantity`    : 목표 수량 = floor(고정 base × weight% / 100 / referencePrice). base·price 미정 시 null.
 *     - `triggerDescription`: 진입 조건 사람 설명(1차=즉시 / 2차↑=시그널 재발화 또는 -X% 도달).
 * - `expectedStopLossPrice`: 1차 평단 -손절% 예상 손절가(분할 완료 후 적용 — ⑥). 평단·손절% 미정 시 null.
 *   (분할 진행되며 평단이 바뀌면 화면이 갱신 호출로 재계산 — 본 함수는 referenceAvgPrice 기준 1회 계산.)
 * - `floorPrice`           : catastrophic floor 도달가 = referenceAvgPrice × (1 − floorPct/100).
 * - `floorAutoLiquidate`   : floor 도달 시 자동 청산 여부(false=알람만 default ⑦ / true=자동 청산 갭②) — UI 라벨용.
 *
 * 1차 미진입(referenceAvgPrice/base 미정)이면 targetQuantity·가격류는 null 로 두고 비중·조건만 노출한다(미리 보기).
 */
object ScaleInPlanProjector {
    private val HUNDRED = BigDecimal("100")

    /**
     * @param plan              분할 매수 정책.
     * @param fixedStopLossPct  평단 대비 손절%(전략 fixedStopLoss). null 이면 예상 손절가 미산출.
     * @param baseBudget        고정 base(1차 시점 buyable 박제 또는 미리 보기 추정). null 이면 목표 수량 미산출(비중만).
     * @param referencePrice    회차 수량 산정 기준가(1차 진입가 또는 현재가). null 이면 목표 수량 미산출.
     * @param referenceAvgPrice 예상 손절가·floor 가 산정 기준 평단(1차 평단 또는 미리 보기 진입가). null 이면 가격류 미산출.
     */
    fun project(
        plan: ScaleInPlan,
        fixedStopLossPct: BigDecimal?,
        baseBudget: BigDecimal?,
        referencePrice: BigDecimal?,
        referenceAvgPrice: BigDecimal?,
    ): ScaleInPlanPreview {
        val weights = plan.resolvedWeights()
        var displayOrder = 0
        val slices =
            weights.mapIndexedNotNull { index, weight ->
                if (weight <= BigDecimal.ZERO) return@mapIndexedNotNull null // ⑨ — 0% 회차 제외
                displayOrder++
                val targetQty =
                    if (baseBudget != null && baseBudget > BigDecimal.ZERO && referencePrice != null && referencePrice > BigDecimal.ZERO) {
                        baseBudget
                            .multiply(weight)
                            .divide(HUNDRED, 0, RoundingMode.DOWN)
                            .divide(referencePrice, 0, RoundingMode.DOWN)
                            .toLong()
                            .coerceAtLeast(0)
                    } else {
                        null
                    }
                ScaleInSlicePreview(
                    sliceIndex = index,
                    displayOrder = displayOrder,
                    weightPct = weight,
                    targetQuantity = targetQty,
                    triggerDescription = triggerDescription(displayOrder, plan),
                )
            }

        val expectedStopLoss =
            if (fixedStopLossPct != null &&
                fixedStopLossPct > BigDecimal.ZERO &&
                referenceAvgPrice != null &&
                referenceAvgPrice > BigDecimal.ZERO
            ) {
                referenceAvgPrice.multiply(BigDecimal.ONE.subtract(fixedStopLossPct.divide(HUNDRED, 8, RoundingMode.HALF_UP)))
            } else {
                null
            }
        val floorPct = plan.resolvedFloorPct()
        val floorPrice =
            if (floorPct > BigDecimal.ZERO && referenceAvgPrice != null && referenceAvgPrice > BigDecimal.ZERO) {
                referenceAvgPrice.multiply(BigDecimal.ONE.subtract(floorPct.divide(HUNDRED, 8, RoundingMode.HALF_UP)))
            } else {
                null
            }

        return ScaleInPlanPreview(
            totalSlices = plan.totalSlices,
            effectiveSlices = plan.effectiveSliceCount(),
            slices = slices,
            expectedStopLossPrice = expectedStopLoss,
            fixedStopLossPct = fixedStopLossPct,
            floorPct = floorPct,
            floorPrice = floorPrice,
            floorAutoLiquidate = plan.floorAutoLiquidate,
        )
    }

    private fun triggerDescription(
        displayOrder: Int,
        plan: ScaleInPlan,
    ): String =
        if (displayOrder == 1) {
            "1차 진입 — BUY 시그널 발화 시 즉시"
        } else {
            when (plan.reentryTrigger) {
                ReentryTrigger.SIGNAL_REPEAT -> "${displayOrder}차 — BUY 시그널 재발화 시"
                ReentryTrigger.PRICE_DROP_PCT ->
                    "${displayOrder}차 — 직전 회차 체결가 대비 ${plan.priceDropPct?.toPlainString() ?: "-"}% 하락 시"
            }
        }
}

/**
 * 분할 매수 계획 미리 보기(frontend DTO) — 0% 회차 제외(⑨). api/BFF 가 직렬화해 화면에 노출한다.
 */
data class ScaleInPlanPreview(
    /** 설정된 총 회차 수(0% 포함). */
    val totalSlices: Int,
    /** 실효 회차 수(0% 제외 — slices.size 와 동일). */
    val effectiveSlices: Int,
    /** 회차별 계획(0% 회차 제외). */
    val slices: List<ScaleInSlicePreview>,
    /** 예상 손절가 = 기준 평단 × (1 − 손절%/100). 평단·손절% 미정 시 null. */
    val expectedStopLossPrice: BigDecimal?,
    /** 손절% (전략 설정값). */
    val fixedStopLossPct: BigDecimal?,
    /** catastrophic floor% (양수). */
    val floorPct: BigDecimal,
    /** floor 도달가 = 기준 평단 × (1 − floor%/100). [floorAutoLiquidate]에 따라 알람만(⑦) 또는 자동 청산. 평단 미정 시 null. */
    val floorPrice: BigDecimal?,
    /** floor 도달 시 자동 전량 청산 여부 (ADR 0128 amend 갭②). false = 알람만(default, ⑦). true = 자동 청산. UI 라벨용. */
    val floorAutoLiquidate: Boolean = false,
)

/**
 * 분할 매수 회차 1건 계획.
 */
data class ScaleInSlicePreview(
    /** 0-기반 원본 회차 인덱스(reason `#{sliceIndex+1}` 정합). 0% skip 시 비연속 가능. */
    val sliceIndex: Int,
    /** 화면 표시 순번(1-기반, 0% 제외 후). */
    val displayOrder: Int,
    /** 회차 비중%. */
    val weightPct: BigDecimal,
    /** 목표 수량 = floor(고정 base × weight% / 100 / 기준가). base·기준가 미정 시 null. */
    val targetQuantity: Long?,
    /** 진입 조건 사람 설명. */
    val triggerDescription: String,
)

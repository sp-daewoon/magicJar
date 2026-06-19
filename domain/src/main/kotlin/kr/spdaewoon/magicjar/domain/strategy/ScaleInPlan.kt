package kr.spdaewoon.magicjar.domain.strategy

import java.math.BigDecimal

/**
 * 분할 매수(scale-in) 정책 — spec §3-2 · ADR 0111 §2-1.
 *
 * 전략당 진입을 N회로 나누고(`totalSlices`), 회당 비중(`sliceWeights`)을 조절한다.
 * scale-in 미설정 전략([StrategyDefinition.scaleIn] == null)은 현행 1회 진입 그대로(하위호환 LOCK).
 *
 * 이 클래스는 **순수 도메인** — Spring·JPA·Jackson 어노테이션 import 절대 금지(헥사고날 정합).
 * 단 jackson이 별도 어노테이션 없이 직렬화할 수 있도록 평탄한 데이터 클래스 형태를 유지한다(R2 영속 wiring).
 *
 * ## 비중(`sliceWeights`) 의미 (spec §3-2)
 * 비중은 **전략 종목 예산(RiskConfig POSITION_LIMIT budget)에 대한 분배**다 — budget을 넘기지 않는다.
 * 회차 수량 = `floor(종목예산 × sliceWeight[filledLots]% / priceAtSignal)`(산정은 R2 [ScaleInExitCalculator]).
 * `null`이면 `100 / totalSlices` 균등 분할(사용자 결재 default, 2026-06-05).
 *
 * ## 진행도 (spec §3-2 · R2 책임)
 * `StrategyPosition.filledLots < totalSlices`일 때만 추가 진입.
 * `filledLots == totalSlices`면 scale-in 완료(이후 BUY 시그널 무시 — 평단 오염 방지).
 *
 * ## strategy 테이블 `scale_in_config` JSONB 직렬화 예시 (V66 · wiring은 R2)
 * ```json
 * {
 *   "totalSlices": 3,
 *   "sliceWeights": [50, 30, 20],
 *   "reentryTrigger": "PRICE_DROP_PCT",
 *   "priceDropPct": -3.0,
 *   "minSliceIntervalSec": 120
 * }
 * ```
 * 균등 분할(`sliceWeights` 생략) 예시:
 * ```json
 * { "totalSlices": 3, "reentryTrigger": "SIGNAL_REPEAT", "minSliceIntervalSec": 60 }
 * ```
 *
 * @param totalSlices 분할 회수 N. 사용자 결재 cap = **1..10**(2026-06-05). 1이면 단일 진입(현행 동작).
 * @param sliceWeights 회당 비중%(합 = 100±0.01). null이면 균등 분할(100/totalSlices). 명시 시 개수 = totalSlices.
 * @param reentryTrigger 추가 진입 조건 (default [ReentryTrigger.SIGNAL_REPEAT]).
 * @param priceDropPct [ReentryTrigger.PRICE_DROP_PCT] 시 직전 회차 체결가 대비 하락폭%(음수, 예: -3.0). 그 외 트리거에선 null.
 * @param minSliceIntervalSec 회차 간 최소 간격(초) — 과다 진입 방지. default 60. null이면 default 적용(R2).
 * @param stopLossFloorPct **catastrophic floor**(양수%, 평단 대비 손실폭) — 분할 미완 동안 평단 손절을 보류하되
 *   이 깊이에 도달하면 **Slack 알람만** 발행한다(자동 청산 X — 2026-06-19 사용자 결재 ⑦, ADR 0128). 청산은 사용자
 *   수동 판단. null이면 [ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT]. ≤0이면 floor 비활성(분할 완료까지 순수 보류,
 *   알람도 없음). **저장 시 검증**: floor 도달가는 분할 최저 진입가보다 아래여야 한다 — api 저장 경로
 *   ([ScaleInLimits.validateFloorBelowDeepestEntry])에서 검증. 안 그러면 분할이 floor에 막혀 완료 불가.
 *
 * ### ⑨ weight 0 회차 허용 (2026-06-19 사용자 결재)
 * `sliceWeights`의 일부가 `0`(예 [50,50,0])이어도 검증 통과한다. 0% 회차는 회차 예산 0 → 0주 → 엔진이 **skip**
 * 처리해 빈 주문·filledLots 꼬임 없이 totalSlices=2 [50,50]와 동일 결과가 된다. (음수만 금지, 합은 양수여야 한다.)
 */
data class ScaleInPlan(
    val totalSlices: Int,
    val sliceWeights: List<BigDecimal>? = null,
    val reentryTrigger: ReentryTrigger = ReentryTrigger.SIGNAL_REPEAT,
    val priceDropPct: BigDecimal? = null,
    val minSliceIntervalSec: Int? = ScaleInLimits.DEFAULT_MIN_SLICE_INTERVAL_SEC,
    val stopLossFloorPct: BigDecimal? = null,
) {
    init {
        require(totalSlices in ScaleInLimits.MIN_SLICES..ScaleInLimits.MAX_SLICES) {
            "totalSlices must be in ${ScaleInLimits.MIN_SLICES}..${ScaleInLimits.MAX_SLICES}: $totalSlices"
        }
        sliceWeights?.let { weights ->
            require(weights.size == totalSlices) {
                "sliceWeights size must equal totalSlices: weights=${weights.size}, totalSlices=$totalSlices"
            }
            // ⑨ weight 0 회차 허용 — 음수만 금지(0% 회차는 엔진이 skip). 합이 양수임은 아래 sum 검증이 보장.
            require(weights.all { it >= BigDecimal.ZERO }) {
                "each sliceWeight must be non-negative (0 allowed = skipped slice): $weights"
            }
            val sum = weights.fold(BigDecimal.ZERO, BigDecimal::add)
            require((sum - ScaleInLimits.WEIGHT_TOTAL).abs() <= ScaleInLimits.WEIGHT_SUM_TOLERANCE) {
                "sliceWeights must sum to 100 (±${ScaleInLimits.WEIGHT_SUM_TOLERANCE}): sum=$sum"
            }
        }
        if (reentryTrigger == ReentryTrigger.PRICE_DROP_PCT) {
            val drop =
                requireNotNull(priceDropPct) {
                    "priceDropPct is required when reentryTrigger=PRICE_DROP_PCT"
                }
            require(drop < BigDecimal.ZERO) {
                "priceDropPct must be negative (a price drop): $drop"
            }
        }
        minSliceIntervalSec?.let {
            require(it >= 0) { "minSliceIntervalSec must be >= 0: $it" }
        }
        stopLossFloorPct?.let {
            require(it >= BigDecimal.ZERO) { "stopLossFloorPct must be >= 0 (0 disables floor): $it" }
        }
    }

    /**
     * catastrophic floor% 해석값 — 명시값 없으면 [ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT].
     * 엔진([ScaleInExitCalculator])과 검증([ScaleInLimits.validateFloorBelowDeepestEntry])이 같은 값을 쓰도록 단일 출처.
     */
    fun resolvedFloorPct(): BigDecimal = stopLossFloorPct ?: ScaleInLimits.DEFAULT_STOP_LOSS_FLOOR_PCT

    /**
     * 회차별 비중% 리스트로 해석한다. `sliceWeights`가 null이면 `100/totalSlices` 균등 분배.
     * 균등 분배 시 나머지(반올림 오차)는 마지막 회차에 흡수해 합이 정확히 100이 되도록 한다.
     * (수량 산정의 base — 실제 수량 floor 적용은 R2 [ScaleInExitCalculator] 책임.)
     */
    fun resolvedWeights(): List<BigDecimal> {
        sliceWeights?.let { return it }
        val even = ScaleInLimits.WEIGHT_TOTAL.divide(BigDecimal(totalSlices), 4, java.math.RoundingMode.DOWN)
        val head = List(totalSlices - 1) { even }
        val last = ScaleInLimits.WEIGHT_TOTAL - even.multiply(BigDecimal(totalSlices - 1))
        return head + last
    }
}

/**
 * 추가 진입(재진입) 트리거 — spec §3-2 · ADR 0111 §2-1.
 *
 * scale-in 추가 회차를 무엇으로 발화할지 결정한다. DSL 신규 연산자는 도입하지 않는다(spec §6-4 LOCK).
 */
enum class ReentryTrigger {
    /** BUY 시그널이 다시 발화하면 다음 회차 진입 (default — 기존 BUY DSL 재발화 재사용). */
    SIGNAL_REPEAT,

    /** 직전 회차 체결가 대비 [ScaleInPlan.priceDropPct]% 하락 시 다음 회차 진입 (averaging down). */
    PRICE_DROP_PCT,
}

/**
 * 분할 익절(partial take-profit) 단계 — spec §3-3 · ADR 0111 §2-2.
 *
 * 평단([StrategyPosition.avgPrice]) 대비 [triggerProfitPct] 수익률 도달 시 보유 수량의 [exitWeightPct]%만 청산한다.
 * 단계별 1회만 발화(중복 SELL 방지)는 R2 엔진 + `StrategyPosition.executedExitStages` 책임(spec §3-1).
 * 미설정([StrategyDefinition.partialExits] == null)이면 현행 전량 익절 그대로(하위호환 LOCK).
 *
 * 순수 도메인 — Spring·JPA·Jackson 어노테이션 import 금지. jackson 직렬화 호환 평탄 데이터 클래스.
 *
 * ## strategy 테이블 `partial_exits` JSONB 직렬화 예시 (V66 · wiring은 R2)
 * ```json
 * [
 *   { "triggerProfitPct": 3.0, "exitWeightPct": 50 },
 *   { "triggerProfitPct": 7.0, "exitWeightPct": 30 }
 * ]
 * ```
 *
 * @param triggerProfitPct 평단 대비 이 수익률%(양수) 도달 시 발화.
 * @param exitWeightPct 보유 수량의 이 비율%(0 초과 ~ 100 이하) 청산. 단계 weight 합 ≤ 100([ExitLimits.validatePartialExits]).
 */
data class PartialExit(
    val triggerProfitPct: BigDecimal,
    val exitWeightPct: BigDecimal,
) {
    init {
        require(triggerProfitPct > BigDecimal.ZERO) {
            "triggerProfitPct must be positive: $triggerProfitPct"
        }
        require(exitWeightPct > BigDecimal.ZERO && exitWeightPct <= ExitLimits.WEIGHT_TOTAL) {
            "exitWeightPct must be in (0, ${ExitLimits.WEIGHT_TOTAL}]: $exitWeightPct"
        }
    }
}

/**
 * scale-in 사용자 결재 검증 상수 (2026-06-05 확정 — spec §6-4 · ADR 0111 §2-1).
 */
object ScaleInLimits {
    /** 분할 회수 최소 (1 = 단일 진입 = 현행). */
    const val MIN_SLICES: Int = 1

    /** 분할 회수 cap (사용자 결재 2026-06-05 = 10). */
    const val MAX_SLICES: Int = 10

    /** 회차 간 최소 간격 default(초). */
    const val DEFAULT_MIN_SLICE_INTERVAL_SEC: Int = 60

    /** 비중 합 목표값(%). */
    val WEIGHT_TOTAL: BigDecimal = BigDecimal("100")

    /** 비중 합 허용 오차(±). 사용자 결재 = 100±0.01. */
    val WEIGHT_SUM_TOLERANCE: BigDecimal = BigDecimal("0.01")

    /**
     * catastrophic floor default(%, 양수 = 평단 대비 손실폭). 분할 미완 동안 손절 보류하다 이 깊이에서 **알람만**
     * 발행(자동 청산 X — 2026-06-19 사용자 결재 ⑦). 통상 fixedStopLossPct(예 5%)보다 충분히 깊게(15%) 잡아
     * 정상 물타기 구간에선 닿지 않게 한다. (ScaleInExitCalculator 와 동일 값 — 단일 출처.)
     */
    val DEFAULT_STOP_LOSS_FLOOR_PCT: BigDecimal = BigDecimal("15")

    /**
     * 저장 시 검증 — **floor 도달가가 분할 최저 진입가보다 아래**인가 (2026-06-19 사용자 결재 ⑦).
     *
     * ## 왜 검증하나
     * floor 가 분할 최저 진입가보다 **위**(얕)면, 마지막 회차를 진입하기도 전에 floor 알람이 떠 분할의 의미가 깨진다.
     * (floor 가 분할 완료를 막지는 않지만 — 알람만이므로 — 잘못 설정한 사용자를 저장 시점에 차단해 운영 혼선을 막는다.)
     *
     * ## 분할 최저 진입가 추정 (PRICE_DROP_PCT 트리거 기준)
     * PRICE_DROP_PCT 면 회차마다 직전 체결가 대비 [priceDropPct]% 더 떨어져야 진입하므로, N회 분할의 최저 진입가는
     * 1차가 1.0 일 때 약 `(1 + drop/100)^(totalSlices-1)`(누적 하락). 평단은 그 사이 어딘가지만, 보수적으로 floor 가
     * **마지막 회차 진입가보다 더 깊어야**(아래) 한다는 것을 1차 진입가 기준 누적 하락폭과 비교한다.
     *   - 최저 진입가 누적 하락폭% = `(1 - (1+drop/100)^(N-1)) × 100` (양수, 1차 대비 하락 깊이).
     *   - floor 깊이%(평단 대비)는 1차 대비로 환산하면 floor 가 더 보수적이어야 하므로 `floorPct >= 최저진입 누적하락%`.
     * SIGNAL_REPEAT 트리거는 가격 조건이 없어(같은 가격대 분할 가능) 이 검증을 적용하지 않는다(항상 통과).
     *
     * @return null = 검증 통과. 비-null = 위반 사유 메시지(api 저장 경로가 400 으로 거부).
     */
    fun validateFloorBelowDeepestEntry(plan: ScaleInPlan): String? {
        if (plan.reentryTrigger != ReentryTrigger.PRICE_DROP_PCT) return null
        val drop = plan.priceDropPct ?: return null // PRICE_DROP_PCT 면 init 이 non-null 보장
        val floorPct = plan.resolvedFloorPct()
        if (floorPct <= BigDecimal.ZERO) return null // floor 비활성 — 검증 skip
        // 최저 진입가 누적 하락폭% = (1 - (1 + drop/100)^(N-1)) × 100. drop 은 음수.
        val ratio = BigDecimal.ONE.add(drop.divide(WEIGHT_TOTAL, 8, java.math.RoundingMode.HALF_UP))
        var cumulative = BigDecimal.ONE
        repeat(plan.totalSlices - 1) { cumulative = cumulative.multiply(ratio) }
        val deepestDropPct = BigDecimal.ONE.subtract(cumulative).multiply(WEIGHT_TOTAL)
        return if (floorPct < deepestDropPct) {
            "stopLossFloorPct($floorPct%) must be deeper than the deepest planned slice entry " +
                "(~${deepestDropPct.setScale(2, java.math.RoundingMode.HALF_UP)}% below the 1st entry) — " +
                "otherwise the floor alert fires before scale-in can complete"
        } else {
            null
        }
    }
}

/**
 * 분할 익절 사용자 결재 검증 상수 (2026-06-05 확정 — spec §3-3 · ADR 0111 §2-2).
 */
object ExitLimits {
    /** 분할 익절 단계 cap (사용자 결재 2026-06-05 = 5). */
    const val MAX_PARTIAL_EXIT_STAGES: Int = 5

    /** 익절 비중 합 상한(%). 분할 익절 weight 합 ≤ 100. */
    val WEIGHT_TOTAL: BigDecimal = BigDecimal("100")

    /**
     * 분할 익절 단계 리스트를 검증한다 — 단계 cap(≤5) + weight 합 ≤ 100.
     * [StrategyDefinition.init]에서 호출(개별 [PartialExit]의 단일 검증은 PartialExit.init 책임).
     *
     * @throws IllegalArgumentException 단계 cap 초과 또는 weight 합 > 100
     */
    fun validatePartialExits(partialExits: List<PartialExit>) {
        require(partialExits.isNotEmpty()) {
            "partialExits must be non-empty when present (use null to disable)"
        }
        require(partialExits.size <= MAX_PARTIAL_EXIT_STAGES) {
            "partialExits must have at most $MAX_PARTIAL_EXIT_STAGES stages: ${partialExits.size}"
        }
        val sum = partialExits.fold(BigDecimal.ZERO) { acc, e -> acc + e.exitWeightPct }
        require(sum <= WEIGHT_TOTAL) {
            "partialExits exitWeightPct sum must be <= $WEIGHT_TOTAL: sum=$sum"
        }
    }
}

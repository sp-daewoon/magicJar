package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.strategy.entry.KrxTickSize
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 거래 계획(TradePlan) lot 청산 평가기 — ADR 0130 L2 (PlanExecutor 의 순수 두뇌).
 *
 * ## 왜 strategy-engine 순수 함수인가 (헥사고날 LOCK · [MinHoldGuard] 패턴 정합)
 * PlanExecutor(consumer)는 tick/봉 마다 열린 lot 의 [TradePlan] 을 평가해 청산 여부를 판정한다. 그 **판정 로직**
 * (TP/SL/trailing/maxHold/exitConditions 도달 + 우선순위)은 외부 I/O 0 인 순수 결정론이어야 단위 테스트로
 * 각 발화·우선순위·격리를 엄밀히 증명할 수 있다(메모리 `feedback_verification_strict` — "단위테스트≠라이브"의 토대).
 * 따라서 평가는 본 순수 객체가, Kafka 구독·주문 발행·DB write 같은 부수효과는 PlanExecutor(consumer)가 책임진다.
 *
 * ## 우선순위 (자본 보호 우선 — ADR 0130 §실행 모델 LOCK)
 * `SL > MAX_HOLD > exitConditions > TP > TRAIL`. 손절(자본 손실 한계)이 최우선, 그다음 강제 보유 상한(MAX_HOLD),
 * 데이터 청산 조건, 익절, 마지막으로 트레일링. 동시 충족 시 더 높은(보호적) 사유가 채택된다.
 *
 * ## 트레일링·exitConditions
 * - **트레일링**: 활성(`enabled`)이고 `activatePct` 도달 후부터 고점 추적, 고점 대비 `trailPct` 하락 시 발화한다.
 *   고점(highWatermark) 상태는 lot 단위로 PlanExecutor 가 보관해 [PlanEvalState.highWatermark] 로 주입한다(순수성 보존).
 * - **exitConditions**: 데이터 기반 청산 조건 확장점. tick 가격만으로 판정 가능한 rule 만 본 평가기가 dispatch 하고,
 *   캔들·지표 의존 rule 은 R2 범위 밖(no-op — PlanExecutor 가 무시·로그). 미지의 rule 은 보수적으로 미발화.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 평가 (ADR 0023 §2-4 정합)
 */
object TradePlanEvaluator {
    private val HUNDRED = BigDecimal("100")

    /**
     * lot 청산 평가 입력 상태 — PlanExecutor 가 tick/봉 시점에 채워 주입(순수성 보존).
     *
     * @param price 현재 평가 가격(tick/봉 close, > 0). TP/SL/trail/exitCond 도달 판정 base.
     * @param avgPrice 이 lot 의 평단(> 0). 평단 연동 TP/SL 재산정 base(박제값 대신 현 평단 우선 — 분할 평단 갱신 반영).
     * @param elapsedBars 진입 후 경과 봉 수(>= 0). MAX_HOLD 판정. PlanExecutor 가 plan.entry.openedAt + bar interval 로 산출.
     * @param highWatermark 진입 후 관측 최고가(원, lot 단위). 트레일링 추적용. null 이면 트레일링 평가 skip(고점 미확보).
     */
    data class PlanEvalState(
        val price: BigDecimal,
        val avgPrice: BigDecimal,
        val elapsedBars: Int = 0,
        val highWatermark: BigDecimal? = null,
    )

    /** 청산 사유 — plan.exitReason 영속 식별자(ADR 0130 §2 status). */
    enum class ExitReason { SL, MAX_HOLD, EXIT_COND, TP, TRAIL }

    /**
     * 청산 결정. [reason] = 채택된(가장 보호적) 사유, [triggerPrice] = 발화 기준 가격(SL/TP 라인 또는 현재가).
     */
    data class PlanExitDecision(
        val reason: ExitReason,
        val triggerPrice: BigDecimal,
    )

    /**
     * lot 의 [plan] 을 [state] 기준으로 평가 — 청산해야 하면 [PlanExitDecision], 아니면 null.
     *
     * 우선순위 SL > MAX_HOLD > exitConditions > TP > TRAIL 순으로 첫 충족 사유를 반환한다(short-circuit).
     * plan.status 가 OPEN 이 아니면(이미 CLOSING/CLOSED) null(재발화 방지 — caller 멱등과 2중).
     */
    fun evaluate(
        plan: TradePlan,
        state: PlanEvalState,
    ): PlanExitDecision? {
        if (plan.status != kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus.OPEN) return null
        if (state.price <= BigDecimal.ZERO || state.avgPrice <= BigDecimal.ZERO) return null

        // 1. SL (자본 보호 최우선) — slPrice 박제값 또는 평단 연동 slPct.
        stopPriceOf(plan, state.avgPrice)?.let { sl ->
            if (state.price <= sl) return PlanExitDecision(ExitReason.SL, sl)
        }

        // 2. MAX_HOLD — 강제 보유 상한 초과(경과 봉 >= maxHoldBars).
        plan.hold?.maxHoldBars?.let { maxBars ->
            if (maxBars >= 1 && state.elapsedBars >= maxBars) {
                return PlanExitDecision(ExitReason.MAX_HOLD, state.price)
            }
        }

        // 3. exitConditions — 데이터 기반 청산 조건(확장점). tick 가격으로 판정 가능한 rule 만 dispatch.
        if (evaluateExitConditions(plan, state)) {
            return PlanExitDecision(ExitReason.EXIT_COND, state.price)
        }

        // 4. TP — tpPrice 박제값 또는 평단 연동 tpPct.
        targetPriceOf(plan, state.avgPrice)?.let { tp ->
            if (state.price >= tp) return PlanExitDecision(ExitReason.TP, tp)
        }

        // 5. TRAIL — 활성 + activatePct 도달 후 고점 대비 trailPct 하락.
        trailTriggerPriceOf(plan, state)?.let { trailStop ->
            if (state.price <= trailStop) return PlanExitDecision(ExitReason.TRAIL, trailStop)
        }

        return null
    }

    /**
     * 손절 라인 — `slPrice` 박제값 우선, 없으면 평단 연동 `slPct`(평단 × (1 − slPct/100)) → KRX 호가단위 내림.
     * stop=null 또는 둘 다 부재면 null(SL 미설정 — L3 floor·dll 의존).
     */
    fun stopPriceOf(
        plan: TradePlan,
        avgPrice: BigDecimal,
    ): BigDecimal? {
        val stop = plan.stop ?: return null
        // 평단 연동 우선 — 분할 평단 갱신을 반영(박제 slPrice 는 1차 진입가 기준이라 후속 회차 평단과 어긋날 수 있음).
        stop.slPct?.takeIf { it > BigDecimal.ZERO }?.let { pct ->
            val raw = avgPrice.multiply(BigDecimal.ONE.subtract(pct.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
            if (raw <= BigDecimal.ZERO) return BigDecimal.ZERO
            return KrxTickSize.floorToTick(raw)
        }
        return stop.slPrice?.takeIf { it > BigDecimal.ZERO }?.let { KrxTickSize.floorToTick(it) }
    }

    /**
     * 익절 라인 — `tpPrice` 박제값 우선 아님: 평단 연동 `tpPct`(평단 × (1 + tpPct/100)) 우선, 없으면 박제 `tpPrice`.
     * → KRX 호가단위 내림(매도 도달 판정은 `price >= tp` 이므로 내림이 보수적). target=null 또는 둘 다 부재면 null.
     */
    fun targetPriceOf(
        plan: TradePlan,
        avgPrice: BigDecimal,
    ): BigDecimal? {
        val target = plan.target ?: return null
        target.tpPct?.takeIf { it > BigDecimal.ZERO }?.let { pct ->
            val raw = avgPrice.multiply(BigDecimal.ONE.add(pct.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
            return KrxTickSize.floorToTick(raw)
        }
        return target.tpPrice?.takeIf { it > BigDecimal.ZERO }?.let { KrxTickSize.floorToTick(it) }
    }

    /**
     * 트레일링 청산 라인 — 활성 + (activatePct 미설정 또는 고점이 활성 수익률 도달) 후 고점 × (1 − trailPct/100).
     * 미활성·고점 미확보·trailPct 부재면 null(트레일링 미평가).
     */
    fun trailTriggerPriceOf(
        plan: TradePlan,
        state: PlanEvalState,
    ): BigDecimal? {
        val trailing = plan.trailing ?: return null
        if (!trailing.enabled) return null
        val trailPct = trailing.trailPct?.takeIf { it > BigDecimal.ZERO } ?: return null
        val high = state.highWatermark?.takeIf { it > BigDecimal.ZERO } ?: return null

        // activatePct 설정 시 — 고점이 평단 대비 activatePct% 이상 올라야 트레일링 무장(아니면 미발화).
        trailing.activatePct?.takeIf { it > BigDecimal.ZERO }?.let { actPct ->
            val activateLine = state.avgPrice.multiply(BigDecimal.ONE.add(actPct.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
            if (high < activateLine) return null
        }

        val raw = high.multiply(BigDecimal.ONE.subtract(trailPct.divide(HUNDRED, 10, RoundingMode.HALF_UP)))
        if (raw <= BigDecimal.ZERO) return BigDecimal.ZERO
        return KrxTickSize.floorToTick(raw)
    }

    /**
     * exitConditions 평가 — tick 가격만으로 판정 가능한 rule 만 dispatch(확장점 add-only).
     *
     * 현재 지원 rule(가격 기준):
     * - `price_below` / `stop_below` : params["price"] 이하로 떨어지면 발화.
     * - `price_above` / `target_above` : params["price"] 이상으로 오르면 발화.
     *
     * 캔들·지표 의존 rule(예 `vwap_cross_down`)은 R2 tick 평가 범위 밖 → 미발화(no-op). 미지의 rule 도 미발화(보수적).
     */
    private fun evaluateExitConditions(
        plan: TradePlan,
        state: PlanEvalState,
    ): Boolean =
        plan.exitConditions.any { cond ->
            when (cond.rule.trim().lowercase()) {
                "price_below", "stop_below" -> paramPrice(cond.params)?.let { state.price <= it } ?: false
                "price_above", "target_above" -> paramPrice(cond.params)?.let { state.price >= it } ?: false
                else -> false // 캔들/지표 의존 rule·미지 rule — tick 평가 미지원(no-op).
            }
        }

    private fun paramPrice(params: Map<String, Any?>): BigDecimal? =
        when (val raw = params["price"]) {
            is BigDecimal -> raw
            is Number -> BigDecimal(raw.toString())
            is String -> raw.trim().toBigDecimalOrNull()
            else -> null
        }?.takeIf { it > BigDecimal.ZERO }
}

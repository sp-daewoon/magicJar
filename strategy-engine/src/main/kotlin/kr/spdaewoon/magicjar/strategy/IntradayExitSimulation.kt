package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import java.math.BigDecimal
import java.time.LocalTime

/**
 * 초단타 정합 백테스트 청산 시뮬 어댑터 — ADR 0132 §1-D (라이브 [TradePlanEvaluator] 재사용 + intrabar SL 우선).
 *
 * ## parity (청산 재구현 금지 LOCK)
 * 백테스트 청산을 라이브 [TradePlanEvaluator.evaluate] **그대로** 호출해 시뮬한다(우선순위 SL>MAX_HOLD>exitConditions>
 * TP>TRAIL). 청산 로직을 백테스트용으로 재작성하면 drift 가 필연이므로(`feedback_verification_strict` "단위테스트≠
 * 라이브"), 본 객체는 [ScaleInExitSimulation] 형제로서 **라이브 두뇌를 봉 시뮬에 묶는 얇은 어댑터**일 뿐이다.
 *
 * ## intrabar SL 우선 (보수적 — 낙관 편향 차단)
 * 봉 close 만 보면 장중 SL 도달을 놓친다. candle 은 high/low 를 보유하므로 한 봉 안에서(ADR 0132 §D1-2):
 *  1. 봉 low ≤ SL 라인 → **SL 체결**(가격=SL). 같은 봉에서 봉 high ≥ TP 라인이어도 **SL 우선**(손실 먼저).
 *  2. 봉 high ≥ TP 라인 ∧ 봉 low > SL → **TP 체결**(가격=TP).
 *  3. 둘 다 미도달 → 봉 close 로 [TradePlanEvaluator.evaluate](MAX_HOLD/exitCond/TRAIL) 위임.
 * 같은 봉 안의 SL→반등→TP 순서는 알 수 없으므로 SL 우선이 보수적 정답이다(mock 으로 메우지 않음 — 구조적 한계 정직).
 *
 * ## EOD / blackout / min_hold (라이브 상수 단일 출처 [IntradayTradingClock])
 *  - EOD: allow_overnight=false ∧ 봉 ts ≥ 15:15 KST → 봉 close 강제 청산(SL/TP 미발화 시).
 *  - 개장 blackout: 봉 ts ∈ [09:00, 09:15) → 진입 억제(본 객체의 [isOpenBlackout] 판정 · 청산엔 영향 0 — 진입 게이트는 caller).
 *  - min_hold: 진입 후 minHoldBars 봉 동안 SL/TP **외** 청산(MAX_HOLD/exitCond/TRAIL/DSL) 보류. SL/TP 는 항상 작동(라이브 규약).
 *
 * // AI 호출 0 — 룰 기반 순수 함수 (ADR 0023 §2-4 정합)
 */
object IntradayExitSimulation {
    /** EOD 강제 청산 cutoff (KST) — [IntradayTradingClock] 단일 출처 echo. */
    val EOD_LIQUIDATE_KST: LocalTime get() = IntradayTradingClock.EOD_LIQUIDATE_KST

    /** late-entry 차단 cutoff (KST) — [IntradayTradingClock] 단일 출처 echo. */
    val LATE_ENTRY_CUTOFF_KST: LocalTime get() = IntradayTradingClock.LATE_ENTRY_CUTOFF_KST

    /** 개장 blackout 시작 (KST, 포함) — [IntradayTradingClock] 단일 출처 echo. */
    val OPEN_BLACKOUT_FROM: LocalTime get() = IntradayTradingClock.OPEN_BLACKOUT_FROM

    /** 개장 blackout 종료 (KST, 미포함) — [IntradayTradingClock] 단일 출처 echo. */
    val OPEN_BLACKOUT_TO: LocalTime get() = IntradayTradingClock.OPEN_BLACKOUT_TO

    /** 이 봉 ts(KST)에서 owner=AUTOPILOT BUY 가 개장 blackout 으로 억제되는가. SELL/SL/TP 영향 0. */
    fun isOpenBlackout(barTimeKst: LocalTime): Boolean = IntradayTradingClock.isOpenBlackout(barTimeKst)

    /** allow_overnight=false 이고 봉 ts ≥ 15:15 KST → EOD 강제 청산 신호. */
    fun isEodLiquidate(
        allowOvernight: Boolean,
        barTimeKst: LocalTime,
    ): Boolean = IntradayTradingClock.isEodLiquidate(allowOvernight, barTimeKst)

    /**
     * 한 봉(OHLC)에서 plan 청산 평가 — intrabar SL 우선 + EOD/min_hold parity.
     *
     * @param plan 열린 lot 의 거래 계획([TradePlanFactory.build] 결과 · status=OPEN).
     * @param bar 평가 봉(OHLC). [adjusted]=true 면 adj 우선(null 이면 raw fallback), false 면 raw.
     * @param avgPrice 이 lot 평단(> 0) — TradePlanEvaluator 의 SL/TP 평단 연동 base.
     * @param elapsedBars 진입 후 경과 봉 수(>= 0) — MAX_HOLD 판정.
     * @param highWatermark 진입 후 관측 최고가(원, 봉 high 누적). null 이면 트레일링 미평가.
     * @param minHoldBars 최소 보유 봉 수(선택). 0..minHoldBars 구간은 SL/TP 외 청산 보류.
     * @param allowOvernight 오버나잇 허용. false 면 [barTimeKst] >= 15:15 에 EOD 강제 청산.
     * @param barTimeKst 이 봉 ts 의 KST 시각(EOD 판정).
     * @param adjusted 가격 모드 — true=수정주가(adj 우선), false=원주가(raw). caller(R2b 엔진)가 [PriceAdjustmentMode] 로 결정.
     * @return 청산 결정([IntradayExitDecision]) 또는 hold 시 null.
     */
    fun evaluateBar(
        plan: TradePlan,
        bar: Candle,
        avgPrice: BigDecimal,
        elapsedBars: Int,
        highWatermark: BigDecimal?,
        minHoldBars: Int?,
        allowOvernight: Boolean,
        barTimeKst: LocalTime,
        adjusted: Boolean = true,
    ): IntradayExitDecision? {
        if (avgPrice <= BigDecimal.ZERO) return null

        val high = priceOf(bar.highAdj, bar.highRaw, adjusted)
        val low = priceOf(bar.lowAdj, bar.lowRaw, adjusted)
        val close = priceOf(bar.closeAdj, bar.closeRaw, adjusted)
        if (high <= BigDecimal.ZERO || low <= BigDecimal.ZERO || close <= BigDecimal.ZERO) return null

        // SL/TP 라인 — 라이브와 동일 산식([TradePlanEvaluator] 평단 연동·박제값·KRX tick 단일 출처).
        val slLine = TradePlanEvaluator.stopPriceOf(plan, avgPrice)
        val tpLine = TradePlanEvaluator.targetPriceOf(plan, avgPrice)

        // 1. intrabar SL 우선 — 봉 low 가 SL 라인 이하면 SL 체결(같은 봉 TP 도달 무관 · 손실 먼저).
        slLine?.let { sl ->
            if (sl > BigDecimal.ZERO && low <= sl) {
                return IntradayExitDecision(IntradayExitReason.SL, sl)
            }
        }

        // 2. intrabar TP — 봉 high 가 TP 라인 이상 ∧ SL 미도달.
        tpLine?.let { tp ->
            if (high >= tp) {
                return IntradayExitDecision(IntradayExitReason.TP, tp)
            }
        }

        // min_hold 구간 — SL/TP 외 청산(MAX_HOLD/exitCond/TRAIL/EOD) 보류. (SL/TP 는 위에서 이미 평가됨.)
        val withinMinHold = minHoldBars != null && minHoldBars > 0 && elapsedBars < minHoldBars
        if (withinMinHold) return null

        // 3. EOD 강제 청산 — allow_overnight=false ∧ 봉 ts >= 15:15 KST(봉 close 청산).
        if (IntradayTradingClock.isEodLiquidate(allowOvernight, barTimeKst)) {
            return IntradayExitDecision(IntradayExitReason.EOD, close)
        }

        // 4. close 기반 라이브 청산 두뇌 위임 — MAX_HOLD/exitCond/TRAIL (SL/TP 는 close 로도 중복 평가되나 위 intrabar 가 선행).
        val decision =
            TradePlanEvaluator.evaluate(
                plan,
                TradePlanEvaluator.PlanEvalState(
                    price = close,
                    avgPrice = avgPrice,
                    elapsedBars = elapsedBars,
                    highWatermark = highWatermark,
                ),
            ) ?: return null

        return IntradayExitDecision(IntradayExitReason.fromPlan(decision.reason), decision.triggerPrice)
    }

    /** adj 우선(null 이면 raw fallback) 또는 raw 선택. */
    private fun priceOf(
        adj: BigDecimal?,
        raw: BigDecimal,
        adjusted: Boolean,
    ): BigDecimal = if (adjusted) (adj ?: raw) else raw
}

/** 초단타 청산 사유 — [TradePlanEvaluator.ExitReason] + 백테스트 전용 EOD. */
enum class IntradayExitReason {
    SL,
    MAX_HOLD,
    EXIT_COND,
    TP,
    TRAIL,

    /** 장마감 EOD 강제 청산(백테스트 시뮬 — 라이브 [AutopilotEodLiquidationWorker] 대응). */
    EOD,
    ;

    companion object {
        /** [TradePlanEvaluator.ExitReason] → 본 enum 매핑(parity 사유 식별자 보존). */
        fun fromPlan(reason: TradePlanEvaluator.ExitReason): IntradayExitReason =
            when (reason) {
                TradePlanEvaluator.ExitReason.SL -> SL
                TradePlanEvaluator.ExitReason.MAX_HOLD -> MAX_HOLD
                TradePlanEvaluator.ExitReason.EXIT_COND -> EXIT_COND
                TradePlanEvaluator.ExitReason.TP -> TP
                TradePlanEvaluator.ExitReason.TRAIL -> TRAIL
            }
    }
}

/**
 * 초단타 청산 결정 — 발화 사유 + 체결 기준 가격(ADR 0132 §1-D).
 *
 * @param reason 채택된 청산 사유(SL/TP/MAX_HOLD/TRAIL/EXIT_COND/EOD).
 * @param triggerPrice 체결 기준 가격 — SL/TP 는 라인, MAX_HOLD/EOD/TRAIL/EXIT_COND 는 봉 close 또는 trail stop.
 */
data class IntradayExitDecision(
    val reason: IntradayExitReason,
    val triggerPrice: BigDecimal,
)

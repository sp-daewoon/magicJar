package kr.spdaewoon.magicjar.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanContingency
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanHold
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStopType
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTarget
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTrailing
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * 거래 계획(TradePlan) 구성 순수 함수 — ADR 0132 §1-C (라이브 `buildTradePlan` hoist · parity 단일 출처).
 *
 * ## 왜 strategy-engine 순수 함수인가 (drift 0 LOCK)
 * 라이브(`SignalGeneratedKafkaListener.buildTradePlan`)와 백테스트(`IntradayExitSimulation` 진입 박제)가 **같은
 * 규칙**으로 TradePlan 을 구성해야 청산 parity 가 성립한다(ADR 0132 §3 기둥1). 두 곳에 같은 매핑을 복제하면
 * drift 가 필연이므로(`feedback_verification_strict` "단위테스트≠라이브" 함정), plan 구성 로직을 본 순수 객체로
 * 단일화하고 라이브·백테스트가 모두 [build] 를 호출한다.
 *
 * ## 거동 불변 (hoist 리팩터 — 로직 변경 0)
 * 본 함수는 기존 `SignalGeneratedKafkaListener.buildTradePlan` 의 산식을 **byte-동일**하게 옮긴 것이다:
 * - TP 절대가 = entryPrice + (entryPrice × tpPct / 100), `RoundingMode.DOWN`(scale 0).
 * - SL 절대가 = entryPrice − (entryPrice × slPct / 100), `RoundingMode.UP`(scale 0 — 손절은 보수적으로 올림).
 * - trailing 은 `trailPct` 존재 시 `enabled=true`(activatePct 미박제 — 라이브와 동일).
 * - hold 는 `minHoldBars` 존재 시 minHold 만(maxHold 미박제 — 라이브와 동일).
 * - contingency: viAction=정책명, eod=allowOvernight ? "ALLOW_OVERNIGHT" : "LIQUIDATE".
 * - status=OPEN·exitReason=null.
 *
 * // AI 호출 0 — 룰 기반 순수 함수 (ADR 0023 §2-4 정합)
 */
object TradePlanFactory {
    private val HUNDRED = BigDecimal("100")

    /**
     * 전략 설정 + 진입가로 [TradePlan] 을 1회 구성한다 (ADR 0130 §2 스키마 v1 · §6-2 · 라이브와 동일 규칙).
     *
     * TP/SL 절대가는 진입가 기준으로 산출한다(평단 연동 재계산은 PlanExecutor·R2 책임 — 박제값은 출발점).
     * 전략에 미설정인 필드(takeProfitPct·fixedStopLoss·minHoldBars 등)는 해당 하위 객체를 null 로 둔다(확장점 add-only).
     *
     * @param strategyId 이 lot 을 산 전략 id(lot 소유권 귀속 키).
     * @param definition 전략 정의 — TP/SL/trailing/minHold/viAction/allowOvernight 출처.
     * @param entryPrice 진입가(원, > 0) — TP/SL 절대가 산정 base.
     * @param bar 진입 봉 interval 코드(예 "1m"·"D1"). null = 미기록.
     * @param openedAt 진입(박제) 시각 ISO-8601 문자열. null = 미기록.
     * @param thesis 진입 근거 한 줄(선택, 공백이면 null 로 정규화).
     */
    fun build(
        strategyId: Long,
        definition: StrategyDefinition,
        entryPrice: BigDecimal,
        bar: String? = null,
        openedAt: String? = null,
        thesis: String? = null,
    ): TradePlan {
        val tpPct = definition.takeProfitPct?.takeIf { it > BigDecimal.ZERO }
        val slPct = definition.fixedStopLoss?.takeIf { it > BigDecimal.ZERO }
        val trailPct = definition.trailingStop?.takeIf { it > BigDecimal.ZERO }
        val minHold = definition.minHoldBars?.takeIf { it >= 0 }

        val target =
            tpPct?.let {
                TradePlanTarget(
                    tpPct = it,
                    tpPrice = entryPrice.add(entryPrice.multiply(it).divide(HUNDRED, 0, RoundingMode.DOWN)),
                )
            }
        val stop =
            slPct?.let {
                TradePlanStop(
                    type = TradePlanStopType.FIXED,
                    slPct = it,
                    slPrice = entryPrice.subtract(entryPrice.multiply(it).divide(HUNDRED, 0, RoundingMode.UP)),
                )
            }
        val trailing =
            trailPct?.let {
                TradePlanTrailing(enabled = true, trailPct = it)
            }
        val hold = minHold?.let { TradePlanHold(minHoldBars = it) }
        // contingency — 현행 정책(ViActionPolicy·allowOvernight)을 plan 에 동봉해 L3(VI·EOD)가 R3 에서 참조.
        val contingency =
            TradePlanContingency(
                viAction = definition.viActionPolicy.name,
                eod = if (definition.allowOvernight) "ALLOW_OVERNIGHT" else "LIQUIDATE",
            )

        return TradePlan(
            entry =
                TradePlanEntry(
                    strategyId = strategyId,
                    thesis = thesis?.takeIf { it.isNotBlank() },
                    entryPrice = entryPrice,
                    bar = bar,
                    openedAt = openedAt,
                ),
            target = target,
            stop = stop,
            trailing = trailing,
            hold = hold,
            contingency = contingency,
            status = TradePlanStatus.OPEN,
            exitReason = null,
        )
    }
}

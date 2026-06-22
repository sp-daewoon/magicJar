package kr.spdaewoon.magicjar.consumer.autopilot

import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import kr.spdaewoon.magicjar.strategy.autopilot.AutonomousTradingGuard
import kr.spdaewoon.magicjar.strategy.autopilot.AutopilotGuardInput
import kr.spdaewoon.magicjar.strategy.autopilot.AutopilotGuardVerdict
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * magicjar-autopilot Guard **매수 경로 wiring** (spec 2026-06-22 §2-0-A · Phase 0 Task 3).
 *
 * owner=AUTOPILOT 전략의 BUY intent 직전 [AutonomousTradingGuard.evaluate](순수 회로차단기, Task 2)에 외부 상태를
 * 주입하고, 그 verdict 를 매수 경로가 소비할 수 있는 [AutopilotBuyDecision] 으로 옮긴다. **자율 REAL 진입의
 * 유일한 코드 방벽을 실제 자본 경로에 연결**하는 컴포넌트다.
 *
 * ## 적용 대상 (격리 LOCK — zero-regression 핵심)
 * - [MagicjarAutopilotProperties.enabled]=false → 무조건 [AutopilotBuyDecision.NotApplicable](기존 경로 그대로).
 * - signal.side ≠ BUY → NotApplicable(청산·HOLD 무영향).
 * - strategy.owner ≠ AUTOPILOT → NotApplicable(**사용자 수동 전략·후성은 Guard 미적용** — 기존 사이징 byte 동일).
 * - 위 셋을 모두 통과(enabled + BUY + owner=AUTOPILOT)할 때만 Guard 가 개입한다.
 *
 * ## Guard input 수집 (호출자 책임 — Guard 는 순수)
 * - `accountNetWorth` = [RiskContext.currentCapital](순자산 — 기존 잔고/순자산 조회 재사용, 새 KIS 호출 0).
 * - `loopExposure` = owner=AUTOPILOT 보유 평가액 합([StrategyPositionJpaRepository.sumAutopilotExposureByUser]).
 * - `currentLoopPositions` = owner=AUTOPILOT 보유 종목수([countAutopilotHeldCodesByUser]).
 * - `proposedEntryAmount` = 호출자가 산정한 이번 진입 제안 금액(예: resolveBuyQuantity × price 또는 buyable).
 * - `daily/cumRealizedLoss` + `state` = [AutopilotStateStore.load](autopilot_state 권위 행).
 *
 * ## verdict 매핑 (spec §2-0-A·§2-0-B)
 * - [AutopilotGuardVerdict.Allow] → [AutopilotBuyDecision.Allow](approvedAmount). 호출자가 기존 RiskBudget 결과와
 *   **더 보수적 값**(작은 쪽)을 채택한다(둘 중 보수적 우선 — spec §2-0-A).
 * - [AutopilotGuardVerdict.Reject] → [AutopilotBuyDecision.Reject](reason). intent 미발행(호출자가 SystemEvent).
 * - [AutopilotGuardVerdict.KillTriggered] → [AutopilotKillSwitch.trigger] 발동 후 [AutopilotBuyDecision.Kill](reason).
 *   intent 미발행 + 전략 PAUSE + Slack P0.
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 wiring (ADR 0023 §2-4 정합)
 */
@Component
class AutopilotBuyGuard(
    private val properties: MagicjarAutopilotProperties,
    private val stateStore: AutopilotStateStore,
    private val strategyPositionRepository: StrategyPositionJpaRepository,
    private val killSwitch: AutopilotKillSwitch,
) {
    private val log = LoggerFactory.getLogger(AutopilotBuyGuard::class.java)

    /**
     * autopilot 루프 활성 여부(`magicjar.autopilot.enabled`). 호출자가 **owner 미상 신호의 보수 차단 범위**를 결정할 때
     * 본 값을 본다 — enabled=true 면 AUTOPILOT BUY signal 이 실제로 생성되는 구간이므로(buyEvalOwners 에 AUTOPILOT 포함)
     * owner 를 확정하지 못하는 BUY 는 autopilot 가능성을 배제할 수 없어 **Reject**(fail-closed). enabled=false 면
     * buyEvalOwners=['USER'] 라 AUTOPILOT BUY 자체가 생성되지 않으므로 owner 미상 BUY 는 USER 로 보고 기존 경로 진행.
     */
    val enabled: Boolean
        get() = properties.enabled

    /** owner=AUTOPILOT BUY 인지(게이트 + side + owner). false면 호출자는 기존 경로 그대로(NotApplicable 판정과 동일). */
    fun applies(
        signal: Signal,
        strategy: Strategy?,
    ): Boolean =
        properties.enabled &&
            signal.side == SignalSide.BUY &&
            strategy?.owner == StrategyOwner.AUTOPILOT

    /**
     * owner=AUTOPILOT BUY 인지(**enabled 무관** — side + owner 만). [applies] 와 달리 게이트를 보지 않는다.
     *
     * 벨트앤서스펜더(spec 2026-06-22 §2-B 방어 심층)용 — enabled=false 인데 AUTOPILOT BUY 가 **어떤 경로로든**
     * (청산 폴백으로 평가 합류 후 평가기 BUY suppress 누락 등) [evaluate] 에 들어오면 [applies]=false 이지만 본
     * 판정으로 Reject 한다(NotApplicable→Proceed→무게이트 실주문 위험 차단).
     *
     * 호출자([SignalGeneratedKafkaListener.applyAutopilotBuyGuard])는 [applies] 가 아니라 **본 판정으로** evaluate
     * 진입을 결정해야 한다 — applies(enabled 포함) 단축 return 은 enabled=false 의 차단 분기를 건너뛰게 만든다.
     */
    fun isAutopilotBuy(
        signal: Signal,
        strategy: Strategy?,
    ): Boolean =
        signal.side == SignalSide.BUY &&
            strategy?.owner == StrategyOwner.AUTOPILOT

    /**
     * owner=AUTOPILOT BUY 진입 게이트. [applies] 가 false 면 [AutopilotBuyDecision.NotApplicable].
     *
     * ## 벨트앤서스펜더 (spec 2026-06-22 §2-B — 방어 심층)
     * **enabled=false 인데 AUTOPILOT BUY** 가 들어오면 NotApplicable(Proceed)이 아니라 **Reject** 한다. 평가 쿼리가
     * enabled 에 연동돼 정상 경로에선 AUTOPILOT BUY signal 자체가 생성되지 않지만, 청산 폴백([findSellEvalByCodeAndInterval])
     * 으로 ACTIVE AUTOPILOT 이 평가에 합류할 때 평가기 BUY suppress 가 누락되면 게이트 없이 실주문될 위험이 있다 —
     * 그 경로를 코드로 봉쇄한다. **SELL 은 영향 없음**(청산 보장). enabled=true 경로는 기존대로 칭 평가.
     *
     * @param proposedEntryAmount 호출자 산정 진입 제안 금액(원, > 0). Guard 가 건당 캡·풀 잔여로 clamp 대상.
     */
    fun evaluate(
        signal: Signal,
        strategy: Strategy?,
        context: RiskContext,
        proposedEntryAmount: BigDecimal,
    ): AutopilotBuyDecision {
        // 벨트앤서스펜더 — enabled=false + AUTOPILOT BUY 는 무조건 차단(실주문 0). applies()보다 먼저 평가.
        if (!properties.enabled && isAutopilotBuy(signal, strategy)) {
            log.warn(
                "autopilot disabled — AUTOPILOT BUY 차단(벨트앤서스펜더): userId={} code={} strategyId={}",
                signal.userId,
                signal.code,
                signal.strategyId,
            )
            return AutopilotBuyDecision.Reject("autopilot disabled — BUY 차단")
        }
        if (!applies(signal, strategy)) return AutopilotBuyDecision.NotApplicable

        val state = stateStore.load()
        val loopExposure =
            try {
                strategyPositionRepository.sumAutopilotExposureByUser(signal.userId)
            } catch (e: Exception) {
                // fail-closed — 노출 미상이면 풀 소진으로 간주(Reject 방향). 자율 REAL 은 보수측이 안전.
                log.error("autopilot loopExposure 조회 실패 — fail-closed Reject: userId={} err={}", signal.userId, e.message)
                return AutopilotBuyDecision.Reject("루프 노출 조회 실패 — fail-closed")
            }
        val loopPositions =
            try {
                strategyPositionRepository.countAutopilotHeldCodesByUser(signal.userId)
            } catch (e: Exception) {
                log.error("autopilot loopPositions 조회 실패 — fail-closed Reject: userId={} err={}", signal.userId, e.message)
                return AutopilotBuyDecision.Reject("루프 보유종목수 조회 실패 — fail-closed")
            }

        val verdict =
            AutonomousTradingGuard.evaluate(
                AutopilotGuardInput(
                    accountNetWorth = context.currentCapital,
                    loopExposure = loopExposure,
                    proposedEntryAmount = proposedEntryAmount,
                    currentLoopPositions = loopPositions,
                    dailyRealizedLoss = state.dailyRealizedLoss,
                    cumRealizedLoss = state.cumRealizedLoss,
                    state = state,
                ),
            )

        log.info(
            "autopilot Guard 평가: userId={} code={} strategyId={} netWorth={} loopExposure={} loopPositions={} proposed={} verdict={}",
            signal.userId,
            signal.code,
            signal.strategyId,
            context.currentCapital.toPlainString(),
            loopExposure.toPlainString(),
            loopPositions,
            proposedEntryAmount.toPlainString(),
            verdict::class.simpleName,
        )

        return when (verdict) {
            is AutopilotGuardVerdict.Allow -> AutopilotBuyDecision.Allow(verdict.approvedAmount)
            is AutopilotGuardVerdict.Reject -> AutopilotBuyDecision.Reject(verdict.reason)
            is AutopilotGuardVerdict.KillTriggered -> {
                killSwitch.trigger(verdict.reason)
                AutopilotBuyDecision.Kill(verdict.reason)
            }
        }
    }
}

/**
 * [AutopilotBuyGuard.evaluate] 결과 — 매수 경로가 소비하는 wiring 판정(sealed).
 *
 * Guard 자체의 [AutopilotGuardVerdict] 와 분리한 이유: 호출자(consumer 매수 경로)는 "기존 경로 그대로([NotApplicable])"
 * 라는 추가 상태가 필요하고, KillTriggered 의 부작용(kill 발동)은 이미 [AutopilotBuyGuard] 가 처리하므로 호출자는
 * 단순히 intent 차단만 하면 된다.
 */
sealed interface AutopilotBuyDecision {
    /** autopilot 비대상(게이트 off·non-BUY·owner≠AUTOPILOT) — 기존 매수 경로 그대로 진행(zero-regression). */
    data object NotApplicable : AutopilotBuyDecision

    /**
     * 진입 승인 — [approvedAmount]만큼 허용(항상 > 0). 호출자가 기존 RiskBudget 수량과 **더 보수적 값**을 채택.
     */
    data class Allow(
        val approvedAmount: BigDecimal,
    ) : AutopilotBuyDecision

    /** 진입 거부(이번 진입만) — intent 미발행 + SystemEvent. 루프 자체는 유지. */
    data class Reject(
        val reason: String,
    ) : AutopilotBuyDecision

    /** kill switch 발동(누적 손실 한도) — kill 이미 영속·PAUSE·Slack 처리됨. 호출자는 intent 차단만. */
    data class Kill(
        val reason: String,
    ) : AutopilotBuyDecision
}

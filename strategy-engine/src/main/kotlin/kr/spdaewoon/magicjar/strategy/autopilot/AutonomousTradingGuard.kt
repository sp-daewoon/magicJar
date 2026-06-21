package kr.spdaewoon.magicjar.strategy.autopilot

import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * magicjar-autopilot 자율 루프의 **유일한 코드 방벽** — 완전 자율 REAL 트레이딩 회로차단기 순수 함수
 * (spec §2-0-A · Phase 0 Task 2).
 *
 * ## 1원칙 — 코드 강제 > 프롬프트 (spec §1)
 * 자율 루프는 체크포인트가 0이다(사람이 막아주지 않음). 따라서 루프의 LLM 추론은 신뢰 대상이 아니며,
 * **자율 REAL 진입의 안전은 전적으로 이 함수가 강제하는 하드 칭(hard limit)이 책임진다.**
 * 프롬프트·스킬 지시로 "칭을 지켜라" 하는 것은 안전장치로 인정하지 않는다(LLM은 hallucinate·drift 한다).
 * `owner=AUTOPILOT` 전략의 **모든 REAL 활성화·매수**는 반드시 [evaluate]를 통과해야 한다.
 *
 * ## 순수성 (LOCK)
 * - Spring·JPA·Jackson·Clock·IO 의존 0. 입력([AutopilotGuardInput]) → 출력([AutopilotGuardVerdict]) 결정론.
 * - 외부 상태(계좌 순자산·루프 노출·손실 누적 등)는 **입력으로 주입**한다(호출자 책임). 함수 내부 조회 0.
 * - 동일 입력 → 동일 출력. 전 구간 [BigDecimal] 정밀 산술(부동소수점 drift 0).
 *
 * ## 기존 풀과의 관계 (spec §2-0-A)
 * 이 Guard는 기존 `RiskBudgetCalculator`(계좌 전체 공유 풀) **위에 더 좁게** 겹쳐 적용된다.
 * 루프 전용 자본 풀은 사용자 수동 전략·후성 오버나잇이 쓰는 자본과 **분리** 계산하며([AutopilotGuardInput.loopExposure]는
 * owner=AUTOPILOT 노출만 합산), 둘 중 더 보수적인 값이 이긴다(호출자가 두 게이트를 모두 통과시킨다).
 *
 * ## 평가 순서 (우선순위 LOCK — 보수적 우선, Phase 0 Task 2)
 * 가장 치명적인 정지 조건부터 순서대로 평가하고, 최초로 걸리는 조건의 verdict를 반환한다:
 *
 * 0. **kill active** — [AutopilotState.killActive]면 즉시 [AutopilotGuardVerdict.Reject]("kill active").
 *    호출자도 kill을 가드하지만 이중 방벽(spec §2-0-B kill 상태에선 REAL 경로 진입 불가).
 * 0-b. **계좌 순자산 ≤0 방어** — [AutopilotGuardInput.accountNetWorth] ≤0이면 모든 %한도 산정 분모가 무의미·위험.
 *    즉시 Reject(자본 산정 불가).
 * 풀 = 순자산 × poolPct/100 (루프 전용 자본). **손실 한도는 순자산이 아니라 풀 기준**이다(spec §2-0-A '매우 타이트').
 * 1. **누적 손실** (최우선 정지 — 가장 치명적) — `cumRealizedLoss >= 풀 × cumLossPct/100`이면
 *    [AutopilotGuardVerdict.KillTriggered]. 자율 루프 전체를 멈춰야 하는 영구 정지 신호.
 * 2. **일일 손실** — `dailyRealizedLoss >= 풀 × dailyLossPct/100`이면 Reject("일일 손실 한도 — 당일 halt").
 *    당일만 정지(다음 거래일 [AutopilotState.lastResetDate] 리셋으로 복귀).
 * 3. **동시 보유 종목수** — `currentLoopPositions >= maxPositions`면 Reject(신규 종목 진입 차단).
 * 4. **풀 상한** — `loopExposure + proposedEntryAmount > 풀`이면 잔여(풀−loopExposure)로
 *    clamp 후보를 만든다. 잔여 ≤0이면 Reject(풀 소진).
 * 5. **건당 캡** — `approvedAmount = min(proposedEntryAmount, 풀잔여, 풀 × perTradePct/100)`.
 *    ≤0이면 Reject, 아니면 [AutopilotGuardVerdict.Allow](approvedAmount).
 *
 * ## 폐기 철학 (spec §3-bis Tier A)
 * autopilot 전용 패키지(`kr.spdaewoon.magicjar.strategy.autopilot`)에 격리된다. 폐기 = 플래그 off + 패키지 제거로
 * 기존 매매 동작은 v3.14.0 그대로(autopilot 미설정 시 zero-regression).
 *
 * // AI 호출 0 — 룰 기반 자본 흐름 순수 함수 (ADR 0023 §2-4 정합)
 */
object AutonomousTradingGuard {
    private val ZERO: BigDecimal = BigDecimal.ZERO
    private val HUNDRED: BigDecimal = BigDecimal("100")

    /** %한도 산정 정밀도(잔여·캡 계산용). 자본 금액 비교 전 충분한 자릿수 확보. */
    private const val SCALE: Int = 4

    /**
     * 자율 루프 진입 단일 게이트. 평가 순서(누적손실 → 일일손실 → 동시보유 → 풀상한 → 건당캡)대로 평가하고
     * 단일 [AutopilotGuardVerdict]를 반환한다. **이 함수가 자율 REAL의 유일한 코드 방벽이다.**
     */
    fun evaluate(input: AutopilotGuardInput): AutopilotGuardVerdict {
        val state = input.state

        // 0. kill active — 이중 방벽(호출자도 가드). REAL 경로 진입 불가(spec §2-0-B).
        if (state.killActive) {
            return AutopilotGuardVerdict.Reject("kill active")
        }

        // 0-b. 계좌 순자산 ≤0 방어 — %한도 분모 무의미. 자본 산정 불가 → 거부.
        val netWorth = input.accountNetWorth
        if (netWorth <= ZERO) {
            return AutopilotGuardVerdict.Reject("계좌 순자산 ≤0 — 자본 산정 불가")
        }

        // 루프 전용 자본 풀 = 순자산 × poolPct/100. 손실 한도·건당 캡의 base (spec §2-0-A — '매우 타이트').
        // ★ 손실 한도는 순자산이 아니라 **풀 기준**이다(2026-06-22 fix): dailyLossPct/cumLossPct 는 풀 대비 %.
        //   순자산 기준이면 누적 50% kill 이 순자산 절반 손실에서야 터져 '매우 타이트'에 위배. 풀(순자산 5%) 기준이면
        //   일일=풀30%=순자산1.5%, 누적=풀50%=순자산2.5% 로 의도대로 타이트하게 bound 된다. (spec §2-0-A 한도표 권위.)
        val poolCeiling = pctOf(netWorth, state.poolPct)

        // 1. 누적 손실 한도 (최우선 정지 — 가장 치명적) → kill switch. 풀 기준.
        val cumLossLimit = pctOf(poolCeiling, state.cumLossPct)
        if (input.cumRealizedLoss >= cumLossLimit) {
            return AutopilotGuardVerdict.KillTriggered(
                "누적 손실 한도 도달 — kill switch (cum=${input.cumRealizedLoss} >= limit=$cumLossLimit, 풀기준)",
            )
        }

        // 2. 일일 손실 한도 → 당일 halt. 풀 기준.
        val dailyLossLimit = pctOf(poolCeiling, state.dailyLossPct)
        if (input.dailyRealizedLoss >= dailyLossLimit) {
            return AutopilotGuardVerdict.Reject(
                "일일 손실 한도 — 당일 halt (daily=${input.dailyRealizedLoss} >= limit=$dailyLossLimit, 풀기준)",
            )
        }

        // 3. 동시 보유 종목수 상한.
        if (input.currentLoopPositions >= state.maxPositions) {
            return AutopilotGuardVerdict.Reject(
                "동시 보유 종목수 한도 (positions=${input.currentLoopPositions} >= max=${state.maxPositions})",
            )
        }

        // 4. 풀 상한 — 루프 전용 자본 풀. 초과 시 잔여로 clamp 후보.
        val poolRemaining = poolCeiling.subtract(input.loopExposure)
        if (poolRemaining <= ZERO) {
            return AutopilotGuardVerdict.Reject(
                "풀 상한 소진 (exposure=${input.loopExposure} >= ceiling=$poolCeiling)",
            )
        }

        // 5. 건당 캡 — min(제안, 풀잔여, 건당캡). 셋 중 가장 보수적 값으로 clamp.
        val perTradeCap = pctOf(poolCeiling, state.perTradePct)
        val approvedAmount =
            input.proposedEntryAmount
                .min(poolRemaining)
                .min(perTradeCap)
        if (approvedAmount <= ZERO) {
            return AutopilotGuardVerdict.Reject(
                "승인 가능 금액 ≤0 (제안=${input.proposedEntryAmount} 풀잔여=$poolRemaining 건당캡=$perTradeCap)",
            )
        }

        return AutopilotGuardVerdict.Allow(approvedAmount)
    }

    /** base × pct/100 — %한도 산정 단일 헬퍼([SCALE] 정밀, HALF_UP). */
    private fun pctOf(
        base: BigDecimal,
        pct: BigDecimal,
    ): BigDecimal = base.multiply(pct).divide(HUNDRED, SCALE, RoundingMode.HALF_UP)
}

/**
 * [AutonomousTradingGuard.evaluate] 입력 — 모든 외부 상태를 명시 주입(순수성 LOCK).
 *
 * 손실 필드는 **양수 = 손실 크기** 규약(spec §2-0-A 한도표 정합)이며 [AutopilotState]의 누적/일일 손실 표현과 일치한다.
 *
 * @property accountNetWorth   계좌 순자산. 풀 상한(=순자산×poolPct/100)의 base이며, 손실 한도는 다시 그 풀 기준. ≤0이면 Reject.
 * @property loopExposure      현재 owner=AUTOPILOT 노출 합(루프 전용 — 사용자 수동 전략·후성 자본 미포함, spec §2-0-A).
 * @property proposedEntryAmount 이번 진입 제안 금액(호출자가 종목예산으로 산정). 건당 캡·풀잔여로 clamp 대상.
 * @property currentLoopPositions 현재 owner=AUTOPILOT 보유 종목수. maxPositions 비교.
 * @property dailyRealizedLoss 당일 실현 손실(양수 = 손실). 일일 halt 비교.
 * @property cumRealizedLoss   누적 실현 손실(양수 = 손실). kill switch 비교.
 * @property state             autopilot 한도 config([AutopilotState]) — killActive·각 % 한도·maxPositions.
 */
data class AutopilotGuardInput(
    val accountNetWorth: BigDecimal,
    val loopExposure: BigDecimal,
    val proposedEntryAmount: BigDecimal,
    val currentLoopPositions: Int,
    val dailyRealizedLoss: BigDecimal,
    val cumRealizedLoss: BigDecimal,
    val state: AutopilotState,
)

/**
 * [AutonomousTradingGuard.evaluate] 출력 — 자율 루프 진입에 대한 단일 판정(sealed).
 *
 * Guard는 자본을 직접 움직이지 않는다 — 호출자(consumer 매수 경로, Task 3)가 [Allow.approvedAmount]로
 * 기존 시그널 → OrderIntent 경로를 통과시키거나, Reject/KillTriggered 시 진입을 차단한다.
 */
sealed interface AutopilotGuardVerdict {
    /**
     * 진입 승인 — clamp 된 [approvedAmount]만큼 매수 허용. 모든 한도를 통과했고 승인 금액 > 0.
     * @property approvedAmount min(제안, 풀잔여, 건당캡) — 항상 > 0.
     */
    data class Allow(
        val approvedAmount: BigDecimal,
    ) : AutopilotGuardVerdict

    /**
     * 진입 거부 — 이번 진입만 차단(루프 자체는 유지). kill active·순자산 무효·일일손실·동시보유·풀 소진·승인액 ≤0.
     * @property reason 거부 사유(SystemEvent·Slack 메시지용).
     */
    data class Reject(
        val reason: String,
    ) : AutopilotGuardVerdict

    /**
     * kill switch 발동 — 누적 손실 한도 도달(가장 치명적). 호출자(Task 3)는 이 verdict를 받으면
     * `autopilot_state.killActive=true` 영속 + owner=AUTOPILOT 전략 전부 PAUSED + REAL freeze + Slack P0.
     * @property reason kill 발동 사유.
     */
    data class KillTriggered(
        val reason: String,
    ) : AutopilotGuardVerdict
}

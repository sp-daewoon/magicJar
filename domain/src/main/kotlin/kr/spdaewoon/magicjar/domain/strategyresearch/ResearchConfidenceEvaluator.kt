package kr.spdaewoon.magicjar.domain.strategyresearch

import kr.spdaewoon.magicjar.domain.backtest.ActivationFailReason
import kr.spdaewoon.magicjar.domain.backtest.AutopilotActivationVerdict
import kr.spdaewoon.magicjar.domain.backtest.BacktestPassCriteria

/**
 * 발굴 후보 신뢰도 판정 순수 함수 (수익 기능 B Phase1 — 2026-09-19).
 *
 * ## 왜 필요한가 (1번 신뢰도 스코어 + 2번 추천의 판정 기준)
 * AI 종목발굴/전략 리서치가 낸 후보([StrategyResearchStats])가 "실제로 돈 될 만한가"를 판정한다. autopilot
 * 활성화 게이트([BacktestPassCriteria])와 **동일한 기준**(승률·양수 기대값·MDD·표본수)을 적용해 일관성을 준다
 * — autopilot에서 통과 못 할 전략이 발굴에서 HIGH로 보이는 모순 방지. verdict/사유 enum도 재사용.
 *
 * ## 단위 차이 (KRW vs %)
 * autopilot [BacktestPassCriteria]는 KRW 금액(netExpectancy KRW) 기준이나, 발굴 stats는 **% 기반**이다
 * (avgTradeReturnPct = 1거래당 평균 순수익률%, 이미 거래비용 차감 — [StrategyResearchEngine] 반영).
 * netExpectancy 부호(> 0 = 양수 기대값)는 단위 무관하게 동일 의미이므로, 승률·MDD·표본 임계치는
 * [BacktestPassCriteria] default를 재사용하고 기대값은 avgTradeReturnPct > 0 으로 판정한다.
 *
 * ## 순수성 (LOCK)
 * Spring·IO·Clock 의존 0. 입력([StrategyResearchStats]) → 출력([ResearchConfidenceVerdict]) 결정론.
 *
 * // AI 호출 0 — 룰 기반 순수 함수 (ADR 0023 §2-4)
 */
object ResearchConfidenceEvaluator {
    /** autopilot 게이트 default 임계치 재사용(일관성 단일 출처). */
    private val CRITERIA = BacktestPassCriteria()

    private val MIN_WIN_RATE_PCT: Double = CRITERIA.minWinRate.toDouble() * 100.0 // 0.40 → 40%
    private val MAX_MDD_PCT: Double = CRITERIA.maxMdd.toDouble() * 100.0 // 0.15 → 15%
    private val MIN_TRADES: Int = CRITERIA.minTrades // 20

    /**
     * 발굴 후보 stats → 신뢰도 판정(PASS/FAIL + 미달 사유 전부 수집).
     *
     * autopilot 게이트와 동일 AND 결합: 표본 ≥ minTrades ∧ 승률 ≥ minWinRate ∧ 순기대(avgTradeReturnPct) > 0
     * ∧ MDD ≤ maxMdd. 하나라도 미달이면 FAIL + 사유(운영 가시성 — 첫 미달 중단 안 함).
     */
    fun evaluate(stats: StrategyResearchStats): ResearchConfidenceVerdict {
        val reasons =
            buildList {
                if (stats.trades < MIN_TRADES) add(ActivationFailReason.INSUFFICIENT_SAMPLE)
                if (stats.winRatePct < MIN_WIN_RATE_PCT) add(ActivationFailReason.LOW_WIN_RATE)
                // 순기대값(1거래당 평균 순수익률%, 비용 차감) > 0 이 진짜 알파. 0 이하면 기대값 음성.
                if (stats.avgTradeReturnPct <= 0.0) add(ActivationFailReason.NEGATIVE_EXPECTANCY)
                if (stats.maxDrawdownPct > MAX_MDD_PCT) add(ActivationFailReason.MDD_EXCEEDED)
            }
        val verdict = if (reasons.isEmpty()) AutopilotActivationVerdict.PASS else AutopilotActivationVerdict.FAIL
        return ResearchConfidenceVerdict(
            verdict = verdict,
            failReasons = reasons,
            winRatePct = stats.winRatePct,
            avgTradeReturnPct = stats.avgTradeReturnPct,
            maxDrawdownPct = stats.maxDrawdownPct,
            trades = stats.trades,
        )
    }
}

/**
 * 발굴 후보 신뢰도 판정 결과 — autopilot [AutopilotActivationVerdict]/[ActivationFailReason] 재사용.
 *
 * @param verdict PASS(믿을 만함)/FAIL(기준 미달).
 * @param failReasons 미달 사유(PASS면 빈 리스트) — frontend 배지·정렬 근거.
 * @param winRatePct 승률%(echo). avgTradeReturnPct 1거래당 순수익률%(echo). maxDrawdownPct MDD%(echo). trades 표본수(echo).
 */
data class ResearchConfidenceVerdict(
    val verdict: AutopilotActivationVerdict,
    val failReasons: List<ActivationFailReason>,
    val winRatePct: Double,
    val avgTradeReturnPct: Double,
    val maxDrawdownPct: Double,
    val trades: Int,
) {
    val passed: Boolean get() = verdict == AutopilotActivationVerdict.PASS
}

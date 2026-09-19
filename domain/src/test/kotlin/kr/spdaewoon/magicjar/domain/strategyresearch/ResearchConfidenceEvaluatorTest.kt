package kr.spdaewoon.magicjar.domain.strategyresearch

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.backtest.ActivationFailReason
import kr.spdaewoon.magicjar.domain.backtest.AutopilotActivationVerdict
import org.junit.jupiter.api.Test

/**
 * ResearchConfidenceEvaluator 판정 테스트 (수익 기능 B Phase1).
 * autopilot 게이트 동일 기준: 표본≥20 ∧ 승률≥40% ∧ 순기대(avgTradeReturnPct)>0 ∧ MDD≤15%.
 */
class ResearchConfidenceEvaluatorTest {
    private fun stats(
        trades: Int = 30,
        winRatePct: Double = 55.0,
        avgTradeReturnPct: Double = 0.3,
        maxDrawdownPct: Double = 8.0,
    ) = StrategyResearchStats(
        trades = trades,
        wins = 0,
        losses = 0,
        winRatePct = winRatePct,
        totalReturnPct = 0.0,
        avgTradeReturnPct = avgTradeReturnPct,
        bestTradePct = 0.0,
        worstTradePct = 0.0,
        maxDrawdownPct = maxDrawdownPct,
        maxConsecutiveLosses = 0,
        profitFactor = null,
    )

    @Test
    fun `모든 기준 충족 — PASS`() {
        val v = ResearchConfidenceEvaluator.evaluate(stats())
        v.verdict shouldBe AutopilotActivationVerdict.PASS
        v.passed shouldBe true
        v.failReasons shouldBe emptyList()
    }

    @Test
    fun `표본 부족 — INSUFFICIENT_SAMPLE FAIL`() {
        val v = ResearchConfidenceEvaluator.evaluate(stats(trades = 19))
        v.passed shouldBe false
        v.failReasons shouldContain ActivationFailReason.INSUFFICIENT_SAMPLE
    }

    @Test
    fun `승률 미달(40퍼 미만) — LOW_WIN_RATE FAIL`() {
        val v = ResearchConfidenceEvaluator.evaluate(stats(winRatePct = 39.9))
        v.passed shouldBe false
        v.failReasons shouldContain ActivationFailReason.LOW_WIN_RATE
    }

    @Test
    fun `순기대값 0 이하 — NEGATIVE_EXPECTANCY FAIL`() {
        val v = ResearchConfidenceEvaluator.evaluate(stats(avgTradeReturnPct = 0.0))
        v.passed shouldBe false
        v.failReasons shouldContain ActivationFailReason.NEGATIVE_EXPECTANCY
    }

    @Test
    fun `MDD 초과(15퍼 초과) — MDD_EXCEEDED FAIL`() {
        val v = ResearchConfidenceEvaluator.evaluate(stats(maxDrawdownPct = 15.1))
        v.passed shouldBe false
        v.failReasons shouldContain ActivationFailReason.MDD_EXCEEDED
    }

    @Test
    fun `여러 기준 동시 미달 — 사유 전부 수집`() {
        val v =
            ResearchConfidenceEvaluator.evaluate(
                stats(trades = 5, winRatePct = 30.0, avgTradeReturnPct = -0.5, maxDrawdownPct = 20.0),
            )
        v.passed shouldBe false
        v.failReasons shouldContainExactlyInAnyOrder
            listOf(
                ActivationFailReason.INSUFFICIENT_SAMPLE,
                ActivationFailReason.LOW_WIN_RATE,
                ActivationFailReason.NEGATIVE_EXPECTANCY,
                ActivationFailReason.MDD_EXCEEDED,
            )
    }

    @Test
    fun `경계값 — 승률 정확히 40퍼, MDD 정확히 15퍼, 표본 정확히 20 — PASS`() {
        val v =
            ResearchConfidenceEvaluator.evaluate(
                stats(trades = 20, winRatePct = 40.0, maxDrawdownPct = 15.0, avgTradeReturnPct = 0.01),
            )
        v.verdict shouldBe AutopilotActivationVerdict.PASS
    }
}

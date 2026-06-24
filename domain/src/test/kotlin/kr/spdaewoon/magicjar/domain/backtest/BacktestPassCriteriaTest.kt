package kr.spdaewoon.magicjar.domain.backtest

import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import kr.spdaewoon.magicjar.domain.market.candle.CandleConfidence
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [BacktestPassCriteria] 단위 테스트 — ADR 0133 §3-B1 합격 기준 경계 + net expectancy 산식 (R1).
 *
 * 검증: 각 기준 PASS/FAIL 경계(거래수·승률·기대값·MDD·정합성) · 복수 미달 사유 수집 · fail-safe 팩토리.
 */
class BacktestPassCriteriaTest {
    private val criteria = BacktestPassCriteria() // default: 20·0.40·>0·0.15·OK

    private fun metrics(
        trades: Int = 30,
        winRate: String = "0.50",
        netExpectancy: String = "1000",
        maxDrawdown: String = "0.10",
        confidence: CandleConfidence = CandleConfidence.OK,
    ) = BacktestPassMetrics(
        trades = trades,
        winRate = BigDecimal(winRate),
        netExpectancy = BigDecimal(netExpectancy),
        maxDrawdown = BigDecimal(maxDrawdown),
        confidence = confidence,
    )

    @Test
    fun `모든 기준 충족 시 PASS`() {
        val result = criteria.evaluate(metrics())
        result.verdict shouldBe AutopilotActivationVerdict.PASS
        result.passed.shouldBeTrue()
        result.failReasons.shouldBe(emptyList())
    }

    @Test
    fun `거래수 경계 — 19는 INSUFFICIENT_SAMPLE FAIL, 20은 통과`() {
        criteria
            .evaluate(metrics(trades = 19))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.INSUFFICIENT_SAMPLE)
        criteria.evaluate(metrics(trades = 20)).passed.shouldBeTrue()
    }

    @Test
    fun `승률 경계 — 0_39는 LOW_WIN_RATE FAIL, 0_40은 통과`() {
        criteria
            .evaluate(metrics(winRate = "0.39"))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.LOW_WIN_RATE)
        criteria.evaluate(metrics(winRate = "0.40")).passed.shouldBeTrue()
    }

    @Test
    fun `net expectancy 경계 — 0과 음수는 NEGATIVE_EXPECTANCY FAIL, 양수만 통과`() {
        criteria
            .evaluate(metrics(netExpectancy = "-1"))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.NEGATIVE_EXPECTANCY)
        // default minExpectancy=0 이므로 정확히 0 도 미달(초과 요구).
        criteria
            .evaluate(metrics(netExpectancy = "0"))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.NEGATIVE_EXPECTANCY)
        criteria.evaluate(metrics(netExpectancy = "1")).passed.shouldBeTrue()
    }

    @Test
    fun `MDD 경계 — 0_16은 MDD_EXCEEDED FAIL, 0_15은 통과`() {
        criteria
            .evaluate(metrics(maxDrawdown = "0.16"))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.MDD_EXCEEDED)
        criteria.evaluate(metrics(maxDrawdown = "0.15")).passed.shouldBeTrue()
    }

    @Test
    fun `정합성 경계 — OK 통과, LOW_CONFIDENCE FAIL, INSUFFICIENT_DATA FAIL`() {
        criteria.evaluate(metrics(confidence = CandleConfidence.OK)).passed.shouldBeTrue()
        criteria
            .evaluate(metrics(confidence = CandleConfidence.LOW_CONFIDENCE))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.LOW_CONFIDENCE)
        criteria
            .evaluate(metrics(confidence = CandleConfidence.INSUFFICIENT_DATA))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.INSUFFICIENT_DATA)
    }

    @Test
    fun `requireConfidence LOW_CONFIDENCE 면 LOW도 통과하되 INSUFFICIENT_DATA는 FAIL`() {
        val lenient = BacktestPassCriteria(requireConfidence = CandleConfidence.LOW_CONFIDENCE)
        lenient.evaluate(metrics(confidence = CandleConfidence.LOW_CONFIDENCE)).passed.shouldBeTrue()
        lenient
            .evaluate(metrics(confidence = CandleConfidence.INSUFFICIENT_DATA))
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.INSUFFICIENT_DATA)
    }

    @Test
    fun `복수 미달 — 사유를 전부 수집한다`() {
        val result =
            criteria.evaluate(
                metrics(
                    trades = 5,
                    winRate = "0.20",
                    netExpectancy = "-500",
                    maxDrawdown = "0.50",
                    confidence = CandleConfidence.INSUFFICIENT_DATA,
                ),
            )
        result.verdict shouldBe AutopilotActivationVerdict.FAIL
        result.passed.shouldBeFalse()
        result.failReasons.shouldContainExactlyInAnyOrder(
            ActivationFailReason.INSUFFICIENT_SAMPLE,
            ActivationFailReason.LOW_WIN_RATE,
            ActivationFailReason.NEGATIVE_EXPECTANCY,
            ActivationFailReason.MDD_EXCEEDED,
            ActivationFailReason.INSUFFICIENT_DATA,
        )
    }

    @Test
    fun `net expectancy 산식 — E = winRate·avgWin − (1−winRate)·avgLoss`() {
        // winRate=0.4, avgWin=3000, avgLoss=2000 → 0.4*3000 - 0.6*2000 = 1200 - 1200 = 0 → 미달.
        val zero =
            BacktestPassMetrics.fromWinLoss(
                trades = 25,
                winRate = BigDecimal("0.40"),
                avgWin = BigDecimal("3000"),
                avgLoss = BigDecimal("2000"),
                maxDrawdown = BigDecimal("0.10"),
                confidence = CandleConfidence.OK,
            )
        zero.netExpectancy.compareTo(BigDecimal.ZERO) shouldBe 0
        criteria
            .evaluate(zero)
            .failReasons
            .shouldContainExactlyInAnyOrder(ActivationFailReason.NEGATIVE_EXPECTANCY)

        // winRate=0.4, avgWin=4000, avgLoss=2000 → 1600 - 1200 = 400 > 0 → 통과(손익비로 저승률 극복).
        val positive =
            BacktestPassMetrics.fromWinLoss(
                trades = 25,
                winRate = BigDecimal("0.40"),
                avgWin = BigDecimal("4000"),
                avgLoss = BigDecimal("2000"),
                maxDrawdown = BigDecimal("0.10"),
                confidence = CandleConfidence.OK,
            )
        positive.netExpectancy.compareTo(BigDecimal("400")) shouldBe 0
        criteria.evaluate(positive).passed.shouldBeTrue()
    }

    @Test
    fun `fail-safe 팩토리 — insufficientData·error는 보수 FAIL`() {
        BacktestPassResult.insufficientData().run {
            verdict shouldBe AutopilotActivationVerdict.FAIL
            failReasons.shouldContainExactlyInAnyOrder(ActivationFailReason.INSUFFICIENT_DATA)
        }
        BacktestPassResult.error().run {
            verdict shouldBe AutopilotActivationVerdict.FAIL
            failReasons.shouldContainExactlyInAnyOrder(ActivationFailReason.BACKTEST_ERROR)
            metrics shouldBe null
        }
    }
}

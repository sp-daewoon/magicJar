package kr.spdaewoon.magicjar.domain.market.sim

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * PercentExitSimulator 순수 함수 테스트 (중복 시뮬레이션 통합 A).
 * SL 우선 + TP + 시간청산 판정을 검증한다.
 */
class PercentExitSimulatorTest {
    private fun bar(
        high: Double,
        low: Double,
        close: Double,
    ) = PercentExitSimulator.Bar(high, low, close)

    @Test
    fun `손절 도달 — STOP_LOSS 체결가는 stop 라인`() {
        // 진입 100, stop 2% = 98. 첫 봉 low 97 <= 98 → 손절.
        val r =
            PercentExitSimulator.simulate(
                entryPrice = 100.0,
                targetProfitPct = 1.0,
                stopLossPct = 2.0,
                bars = listOf(bar(101.0, 97.0, 99.0)),
                maxHoldBars = 4,
            )
        r!!.reason shouldBe PercentExitSimulator.ExitReason.STOP_LOSS
        r.exitPrice shouldBe 98.0
        r.barsFromEntry shouldBe 0
    }

    @Test
    fun `익절 도달 — TAKE_PROFIT 체결가는 target 라인`() {
        // 진입 100, target 1% = 101. 봉 high 102 >= 101, low 99.5 > stop 98 → 익절.
        val r =
            PercentExitSimulator.simulate(
                entryPrice = 100.0,
                targetProfitPct = 1.0,
                stopLossPct = 2.0,
                bars = listOf(bar(102.0, 99.5, 101.5)),
                maxHoldBars = 4,
            )
        r!!.reason shouldBe PercentExitSimulator.ExitReason.TAKE_PROFIT
        r.exitPrice shouldBe 101.0
    }

    @Test
    fun `같은 봉 SL·TP 동시 도달 — SL 우선(보수적)`() {
        // 봉이 low 97(<=98 stop) AND high 102(>=101 target) 둘 다 — SL 우선 체결.
        val r =
            PercentExitSimulator.simulate(
                entryPrice = 100.0,
                targetProfitPct = 1.0,
                stopLossPct = 2.0,
                bars = listOf(bar(102.0, 97.0, 100.0)),
                maxHoldBars = 4,
            )
        r!!.reason shouldBe PercentExitSimulator.ExitReason.STOP_LOSS
        r.exitPrice shouldBe 98.0
    }

    @Test
    fun `미도달 — maxHoldBars 상한 봉 close로 TIME_EXIT`() {
        // SL/TP 어디도 안 닿음. maxHold 2 → offset 2 봉 close 체결.
        val bars =
            listOf(
                bar(100.5, 99.5, 100.0),
                bar(100.6, 99.6, 100.2),
                bar(100.4, 99.7, 100.3),
                bar(100.9, 99.8, 100.5), // maxHold 초과 — 평가 안 됨
            )
        val r =
            PercentExitSimulator.simulate(
                entryPrice = 100.0,
                targetProfitPct = 1.0,
                stopLossPct = 2.0,
                bars = bars,
                maxHoldBars = 2,
            )
        r!!.reason shouldBe PercentExitSimulator.ExitReason.TIME_EXIT
        r.barsFromEntry shouldBe 2
        r.exitPrice shouldBe 100.3
    }

    @Test
    fun `entryPrice 0 또는 빈 bars — null`() {
        PercentExitSimulator.simulate(0.0, 1.0, 2.0, listOf(bar(1.0, 1.0, 1.0)), 4) shouldBe null
        PercentExitSimulator.simulate(100.0, 1.0, 2.0, emptyList(), 4) shouldBe null
    }

    @Test
    fun `maxHoldBars가 bars보다 커도 마지막 봉까지만`() {
        val r =
            PercentExitSimulator.simulate(
                entryPrice = 100.0,
                targetProfitPct = 5.0,
                stopLossPct = 5.0,
                bars = listOf(bar(100.5, 99.5, 100.0), bar(100.6, 99.6, 100.1)),
                maxHoldBars = 100,
            )
        r!!.reason shouldBe PercentExitSimulator.ExitReason.TIME_EXIT
        r.barsFromEntry shouldBe 1 // bars.lastIndex
    }
}

package com.mj.domain.strategy.backtest

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

class PerformanceMetricsCalculatorTest {
    private val ts0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun trade(
        pnl: String,
        days: Long = 1,
    ): Trade {
        val entry = ts0.plusSeconds(days * 86400)
        val exit = entry.plusSeconds(86400)
        // pnl이 직접 주어진 trade 픽스처: entry/exit/qty/cost를 단순화 위해 0 비용 + 단가 계산.
        // entryPrice = 10000, qty = 1, exitPrice = 10000 + pnl
        val pnlBd = BigDecimal(pnl)
        return Trade(
            symbol = "T",
            entryDate = entry,
            exitDate = exit,
            entryPrice = BigDecimal("10000"),
            exitPrice = BigDecimal("10000").add(pnlBd),
            shares = 1,
            pnl = pnlBd,
            pnlPercent = pnlBd.divide(BigDecimal("10000")),
            entryCommission = BigDecimal.ZERO,
            exitCommission = BigDecimal.ZERO,
            exitTax = BigDecimal.ZERO,
            reason = Trade.ExitReason.SIGNAL_EXIT,
        )
    }

    @Test
    fun `total return computed correctly`() {
        val curve = listOf("1000000", "1100000", "1050000", "1200000").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = listOf(trade("100000"), trade("-50000", days = 2)),
                years = BigDecimal("1.0"),
            )
        // (1.2M - 1.0M) / 1.0M = 0.2
        m.totalReturn.toDouble() shouldBe 0.2
        m.tradeCount shouldBe 2
        m.winRate.toDouble() shouldBe 0.5
    }

    @Test
    fun `flat curve produces zero metrics`() {
        val curve = listOf("1000000", "1000000", "1000000").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = emptyList(),
                years = BigDecimal("0.01"),
            )
        m.totalReturn.toDouble() shouldBe 0.0
        m.tradeCount shouldBe 0
        m.winRate.toDouble() shouldBe 0.0
        m.profitFactor.toDouble() shouldBe 0.0
        m.maxConsecutiveLosses shouldBe 0
    }

    @Test
    fun `MDD captures trough after peak`() {
        // peak = 1500, trough = 1200 → MDD = (1200-1500)/1500 = -0.2
        val curve = listOf("1000", "1500", "1200", "1300").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = emptyList(),
                years = BigDecimal("0.5"),
            )
        m.mdd.toDouble() shouldBe -0.2
    }

    @Test
    fun `profit factor = gross profit divided by absolute gross loss`() {
        val trades =
            listOf(
                trade("300"),
                trade("200", days = 2),
                trade("-100", days = 3),
                trade("-50", days = 4),
            )
        val curve = listOf("10000", "10350").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = trades,
                years = BigDecimal("1.0"),
            )
        // gross profit = 500, gross loss = 150 → profitFactor = 500/150 ≈ 3.333333
        (m.profitFactor.toDouble() > 3.33) shouldBe true
        (m.profitFactor.toDouble() < 3.34) shouldBe true
        m.winRate.toDouble() shouldBe 0.5
        m.tradeCount shouldBe 4
    }

    @Test
    fun `profit factor returns large value when no losses`() {
        val trades = listOf(trade("100"), trade("200", days = 2))
        val curve = listOf("10000", "10300").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = trades,
                years = BigDecimal("1.0"),
            )
        // 손실 0 → 큰 값 표시 (999.999999)
        (m.profitFactor.toDouble() > 100.0) shouldBe true
    }

    @Test
    fun `max consecutive losses tracks longest streak`() {
        val trades =
            listOf(
                trade("100"),
                trade("-50", days = 2),
                trade("-30", days = 3),
                trade("-20", days = 4),
                trade("80", days = 5),
                trade("-10", days = 6),
                trade("-15", days = 7),
            )
        val curve = listOf("10000", "10055").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = trades,
                years = BigDecimal("1.0"),
            )
        // 연속 손실: -50, -30, -20 = 3 연속 → 80 끊김 → -10, -15 = 2 연속
        m.maxConsecutiveLosses shouldBe 3
    }

    @Test
    fun `avg win and avg loss computed`() {
        val trades =
            listOf(
                trade("100"),
                trade("200", days = 2),
                trade("-50", days = 3),
                trade("-150", days = 4),
            )
        val curve = listOf("10000", "10100").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = trades,
                years = BigDecimal("1.0"),
            )
        // avgWin = (100+200)/2 = 150
        m.avgWin.toDouble() shouldBe 150.0
        // avgLoss = (50+150)/2 = 100 (절대값)
        m.avgLoss.toDouble() shouldBe 100.0
    }

    @Test
    fun `cagr non-zero for multi-year doubling`() {
        // initial=1M, terminal=2M, 1년 → CAGR = 100%
        val curve = listOf("1000000", "2000000").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = emptyList(),
                years = BigDecimal("1.0"),
            )
        // CAGR ≈ 1.0 (= 100%)
        (m.cagr.toDouble() > 0.99) shouldBe true
        (m.cagr.toDouble() < 1.01) shouldBe true
    }

    @Test
    fun `cagr zero when years is zero`() {
        val curve = listOf("1000000", "2000000").map { BigDecimal(it) }
        val m =
            PerformanceMetricsCalculator.calculate(
                equityCurve = curve,
                trades = emptyList(),
                years = BigDecimal.ZERO,
            )
        m.cagr.toDouble() shouldBe 0.0
    }

    @Test
    fun `empty equity curve rejected`() {
        try {
            PerformanceMetricsCalculator.calculate(
                equityCurve = emptyList(),
                trades = emptyList(),
                years = BigDecimal("1.0"),
            )
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

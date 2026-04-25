package kr.spdaewoon.magicjar.strategy.backtest

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class MetricsCalculatorTest {
    @Test
    fun `total return computed correctly`() {
        val curve = listOf("1000000", "1100000", "1050000", "1200000").map { BigDecimal(it) }
        val m =
            MetricsCalculator.calculate(
                equityCurve = curve,
                roundTrips = listOf(BigDecimal("100000"), BigDecimal("-50000")),
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 12, 31),
            )
        // (1.2M - 1.0M) / 1.0M = 0.2
        m.totalReturn.toDouble() shouldBe 0.2
        m.tradeCount shouldBe 2
        // 1 win out of 2
        m.winRate.toDouble() shouldBe 0.5
        // MDD: peak 1.1M -> 1.05M = -0.045454...
        require(m.mdd.toDouble() < 0.0)
    }

    @Test
    fun `flat curve produces zero metrics`() {
        val curve = listOf("1000000", "1000000", "1000000").map { BigDecimal(it) }
        val m =
            MetricsCalculator.calculate(
                equityCurve = curve,
                roundTrips = emptyList(),
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 1, 3),
            )
        m.totalReturn.toDouble() shouldBe 0.0
        m.tradeCount shouldBe 0
        m.winRate.toDouble() shouldBe 0.0
    }
}

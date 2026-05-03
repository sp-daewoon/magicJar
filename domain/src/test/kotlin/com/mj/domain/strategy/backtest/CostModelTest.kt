package com.mj.domain.strategy.backtest

import com.mj.domain.common.Market
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

class CostModelTest {
    @Test
    fun `default rates match master architecture spec`() {
        val cm = CostModel.DEFAULT
        cm.commissionRate shouldBe BigDecimal("0.00015")
        cm.taxRateKospi shouldBe BigDecimal("0.0018")
        cm.taxRateKosdaq shouldBe BigDecimal("0.0018")
        cm.slippageRate shouldBe BigDecimal("0.0005")
    }

    @Test
    fun `apply buy slippage raises price by slippage rate`() {
        val cm = CostModel.DEFAULT
        val raised = cm.applyBuySlippage(BigDecimal("10000"))
        // 10000 * (1 + 0.0005) = 10005.00
        raised shouldBe BigDecimal("10005.00")
    }

    @Test
    fun `apply sell slippage lowers price by slippage rate`() {
        val cm = CostModel.DEFAULT
        val lowered = cm.applySellSlippage(BigDecimal("10000"))
        // 10000 * (1 - 0.0005) = 9995.00
        lowered shouldBe BigDecimal("9995.00")
    }

    @Test
    fun `buy commission is gross times rate`() {
        val cm = CostModel.DEFAULT
        val commission = cm.buyCommission(BigDecimal("1000000"))
        // 1,000,000 * 0.00015 = 150.0000000000
        commission.toDouble() shouldBe 150.0
    }

    @Test
    fun `sell cost includes commission and tax (KOSPI)`() {
        val cm = CostModel.DEFAULT
        val sc = cm.sellCost(BigDecimal("1000000"), Market.KOSPI)
        sc.commission.toDouble() shouldBe 150.0 // 0.015%
        sc.tax.toDouble() shouldBe 1800.0 // 0.18%
        sc.total.toDouble() shouldBe 1950.0
    }

    @Test
    fun `sell cost differs by market when KOSDAQ rate differs`() {
        val cm =
            CostModel(
                commissionRate = BigDecimal("0.00015"),
                taxRateKospi = BigDecimal("0.0018"),
                taxRateKosdaq = BigDecimal("0.0023"), // 가상 시나리오
                slippageRate = BigDecimal("0.0005"),
            )
        val kospi = cm.sellCost(BigDecimal("1000000"), Market.KOSPI)
        val kosdaq = cm.sellCost(BigDecimal("1000000"), Market.KOSDAQ)
        kospi.tax.toDouble() shouldBe 1800.0
        kosdaq.tax.toDouble() shouldBe 2300.0
    }

    @Test
    fun `zero cost model produces no fees`() {
        val cm = CostModel.ZERO
        cm.applyBuySlippage(BigDecimal("10000")) shouldBe BigDecimal("10000.00")
        cm.applySellSlippage(BigDecimal("10000")) shouldBe BigDecimal("10000.00")
        cm.buyCommission(BigDecimal("1000000")).toDouble() shouldBe 0.0
        cm.sellCost(BigDecimal("1000000"), Market.KOSPI).total.toDouble() shouldBe 0.0
    }

    @Test
    fun `negative rate rejected`() {
        try {
            CostModel(commissionRate = BigDecimal("-0.001"))
            assert(false) { "expected IllegalArgumentException" }
        } catch (e: IllegalArgumentException) {
            // expected
        }
    }
}

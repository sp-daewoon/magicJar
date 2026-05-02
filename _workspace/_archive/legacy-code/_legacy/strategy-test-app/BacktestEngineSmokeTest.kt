package com.mj.domain.strategy.backtest

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.core.domain.BacktestConfig
import kr.spdaewoon.magicjar.core.domain.BacktestRequest
import kr.spdaewoon.magicjar.core.domain.Instrument
import kr.spdaewoon.magicjar.core.domain.Interval
import kr.spdaewoon.magicjar.core.domain.Market
import kr.spdaewoon.magicjar.core.domain.Order
import kr.spdaewoon.magicjar.core.domain.OrderResult
import kr.spdaewoon.magicjar.core.domain.OrderStatus
import kr.spdaewoon.magicjar.core.domain.StrategyRef
import kr.spdaewoon.magicjar.core.port.CandleRepository
import kr.spdaewoon.magicjar.core.port.InstrumentCatalog
import kr.spdaewoon.magicjar.core.port.OrderSender
import com.mj.domain.strategy.CandleFixtures
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class BacktestEngineSmokeTest {
    @Test
    fun `golden cross strategy runs end to end`() {
        val candles = CandleFixtures.loadSampleSamsung()
        val candleRepo = mockk<CandleRepository>()
        every {
            candleRepo.find(eq("005930"), eq(Interval.D1), any(), any())
        } returns candles
        val catalog = mockk<InstrumentCatalog>()
        every { catalog.findByCode("005930") } returns Instrument("005930", "삼성전자", Market.KOSPI)
        // simple in-test OrderSender that fills MARKET orders at next bar open from holder
        val sender =
            object : OrderSender {
                override fun send(order: Order): OrderResult {
                    val ctx = BacktestExecutionContextHolder.current()!!
                    val px = ctx.nextBarOpen!!
                    return OrderResult(
                        order = order.copy(status = OrderStatus.FILLED),
                        resultStatus = OrderStatus.FILLED,
                        filledQty = order.qty,
                        avgFillPrice = px,
                        filledAt = ctx.fillAt,
                    )
                }
            }
        val engine = BacktestEngine(candleRepo, catalog, sender)
        val req =
            BacktestRequest(
                strategy = StrategyRef("golden-cross", 1),
                conditionExpr = "SMA(5) > SMA(20)",
                interval = Interval.D1,
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 6, 30),
                universe = listOf("005930"),
                config =
                    BacktestConfig(
                        initialCapitalKrw = BigDecimal("10000000"),
                        feeRate = BigDecimal("0.00015"),
                        taxRateKospi = BigDecimal("0.0023"),
                        taxRateKosdaq = BigDecimal("0.0023"),
                        slippageBp = BigDecimal("5"),
                    ),
            )
        val result = engine.run(req)
        result.strategy shouldBe req.strategy
        // metrics 객체가 채워졌는지 (NaN/null 없이) 확인
        require(result.metrics.tradeCount >= 0)
        require(result.metrics.totalReturn != null)
        require(result.metrics.mdd.signum() <= 0) { "MDD must be <= 0, got ${result.metrics.mdd}" }
        require(result.metrics.winRate.toDouble() in 0.0..1.0)
    }

    @Test
    fun `empty candles result still produces metrics`() {
        val candleRepo = mockk<CandleRepository>()
        every { candleRepo.find(any(), any(), any(), any()) } returns emptyList()
        val catalog = mockk<InstrumentCatalog>()
        every { catalog.findByCode(any()) } returns null
        val sender =
            object : OrderSender {
                override fun send(order: Order): OrderResult = error("not called")
            }
        val engine = BacktestEngine(candleRepo, catalog, sender)
        val req =
            BacktestRequest(
                strategy = StrategyRef("noop", 1),
                conditionExpr = "CLOSE > 0",
                interval = Interval.D1,
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 1, 31),
                universe = listOf("000000"),
                config =
                    BacktestConfig(
                        initialCapitalKrw = BigDecimal("1000000"),
                        feeRate = BigDecimal("0.00015"),
                        taxRateKospi = BigDecimal("0.0023"),
                        taxRateKosdaq = BigDecimal("0.0023"),
                        slippageBp = BigDecimal("5"),
                    ),
            )
        val result = engine.run(req)
        result.metrics.tradeCount shouldBe 0
    }
}

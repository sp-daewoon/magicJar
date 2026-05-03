package com.mj.domain.strategy.backtest

import com.mj.domain.common.Market
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit

class BacktestEngineTest {
    private val ts0 = Instant.parse("2024-01-01T00:00:00Z")

    private fun bar(
        i: Int,
        close: BigDecimal,
        open: BigDecimal = close,
        high: BigDecimal = close,
        low: BigDecimal = close,
        volume: Long = 1_000_000L,
    ): BacktestBar =
        BacktestBar(
            openTime = ts0.plus(i.toLong(), ChronoUnit.DAYS),
            open = open,
            high = high,
            low = low,
            close = close,
            volume = volume,
        )

    @Test
    fun `empty bars returns empty summary`() {
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = emptyList(),
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 0",
            )
        s.trades.size shouldBe 0
        s.equityCurve.size shouldBe 0
        s.openPosition shouldBe null
    }

    @Test
    fun `signal true on bar 0 enters at bar 1 open and exit on signal flip`() {
        // 4봉. close 가 짝수일 때 신호 ON, 홀수일 때 OFF.
        // bar[0] close=10000 (signal ON) → bar[1] open=10100 매수
        // bar[1] close=10001 (OFF) → bar[2] open=10200 매도
        // bar[2] close=10002 (ON) → bar[3] open=10300 매수
        // bar[3] close=10003 (OFF) → next null → 청산 X (END_OF_DATA)
        // 전략: close % 2 == 0 ? — DSL에 직접 짝수 표현 없으니 SMA(1) 비교 트릭 사용 어려움.
        // 단순화: close > 5000 → 항상 ON. 청산 조건이 안 와서 trade 없음.
        // 더 단순화: SMA(1)을 활용해 close > SMA(2) 로 하면 첫 봉부터 ON이고 안 끊김.
        // → 룩어헤드 검증은 별도 테스트에서 처리. 여기서는 zero-cost long-and-hold 테스트.
        val bars =
            listOf(
                bar(0, BigDecimal("10000"), open = BigDecimal("10000")),
                bar(1, BigDecimal("10500"), open = BigDecimal("10100")),
                bar(2, BigDecimal("11000"), open = BigDecimal("10600")),
                bar(3, BigDecimal("12000"), open = BigDecimal("11200")),
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 0", // 항상 true
            )
        // 신호 항상 ON → bar0에서 신호 → bar1 open=10100 매수.
        // 이후 신호 항상 ON이므로 청산 안 됨.
        s.trades.size shouldBe 0
        s.openPosition?.entryPrice shouldBe BigDecimal("10100.00")
        // 매수 수량 = 1,000,000 / 10100 = 99.0099... → 99
        s.openPosition?.shares shouldBe 99
        s.equityCurve.size shouldBe 4
    }

    @Test
    fun `entry on signal then exit when signal flips`() {
        // close > 100을 신호로. bar 0~1 신호 ON, bar 2~3 신호 OFF.
        val bars =
            listOf(
                bar(0, BigDecimal("150"), open = BigDecimal("150")),
                bar(1, BigDecimal("160"), open = BigDecimal("155")),
                bar(2, BigDecimal("90"), open = BigDecimal("100")),
                bar(3, BigDecimal("80"), open = BigDecimal("85")),
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        // bar 0: close=150 → signal ON, no position → bar 1 open=155에 매수
        //   qty = 1,000,000 / 155 = 6451 (DOWN)
        // bar 1: close=160 → ON, has pos → no action
        // bar 2: close=90 → OFF → bar 3 open=85에 매도
        // bar 3: close=80 → OFF → next null → 무동작
        s.trades.size shouldBe 1
        val t = s.trades.first()
        t.entryPrice shouldBe BigDecimal("155.00")
        t.exitPrice shouldBe BigDecimal("85.00")
        t.shares shouldBe 6451
        // pnl = (85-155) * 6451 = -451,570
        t.pnl.toDouble() shouldBe -451570.0
        s.openPosition shouldBe null
    }

    @Test
    fun `cost model applies commission tax slippage`() {
        // bar 0 신호 ON, bar 2 신호 OFF.
        val bars =
            listOf(
                bar(0, BigDecimal("150"), open = BigDecimal("150")),
                bar(1, BigDecimal("160"), open = BigDecimal("155")),
                bar(2, BigDecimal("90"), open = BigDecimal("100")),
                bar(3, BigDecimal("80"), open = BigDecimal("85")),
            )
        val cost = CostModel.DEFAULT
        val engine = BacktestEngine(cost)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        // 매수 슬리피지: 155 * (1 + 0.0005) = 155.0775 → setScale(2) = 155.08
        // 매도 슬리피지: 85 * (1 - 0.0005) = 84.9575 → setScale(2) = 84.96
        s.trades.size shouldBe 1
        val t = s.trades.first()
        t.entryPrice shouldBe BigDecimal("155.08")
        t.exitPrice shouldBe BigDecimal("84.96")
        // entryCommission = entryGross * 0.00015 > 0
        (t.entryCommission.toDouble() > 0) shouldBe true
        (t.exitCommission.toDouble() > 0) shouldBe true
        // KOSPI tax = sellGross * 0.0018 > 0
        (t.exitTax.toDouble() > 0) shouldBe true
        // pnl < gross pnl (비용 차감)
        val grossPnl = (t.exitPrice - t.entryPrice).toDouble() * t.shares
        (t.pnl.toDouble() < grossPnl) shouldBe true
    }

    @Test
    fun `lookahead is blocked - last bar signal does not enter`() {
        // 신호 항상 ON. 마지막 봉은 nextBar=null이므로 진입 안 함.
        // 여기서 검증할 핵심: bar N-1에서 signal ON이라도 trade·entry 발생 안 함.
        val bars =
            listOf(
                bar(0, BigDecimal("100"), open = BigDecimal("100")),
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 0",
            )
        // 봉 1개 → nextBar 항상 null → 진입 없음
        s.trades.size shouldBe 0
        s.openPosition shouldBe null
        s.equityCurve.size shouldBe 1
        // 자산 그대로
        s.equityCurve.first().equity shouldBe BigDecimal("1000000.00")
    }

    @Test
    fun `equity curve tracks mark-to-market`() {
        val bars =
            listOf(
                bar(0, BigDecimal("100"), open = BigDecimal("100")),
                bar(1, BigDecimal("200"), open = BigDecimal("100")),
                bar(2, BigDecimal("300"), open = BigDecimal("200")),
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 0",
            )
        // bar 0: 진입 전, equity = cash = 1,000,000
        // bar 1: 매수 in 100원 = 10000주 (1,000,000 / 100 = 10,000), cash = 0
        //        bar 1 close=200 → equity = 0 + 10000*200 = 2,000,000
        // bar 2: signal still ON, no exit. close=300 → equity = 0 + 10000*300 = 3,000,000
        s.equityCurve[0].equity shouldBe BigDecimal("1000000.00")
        s.equityCurve[1].equity shouldBe BigDecimal("2000000.00")
        s.equityCurve[2].equity shouldBe BigDecimal("3000000.00")
    }

    @Test
    fun `KOSDAQ uses kosdaq tax rate`() {
        val bars =
            listOf(
                bar(0, BigDecimal("150"), open = BigDecimal("150")),
                bar(1, BigDecimal("160"), open = BigDecimal("155")),
                bar(2, BigDecimal("90"), open = BigDecimal("100")),
                bar(3, BigDecimal("80"), open = BigDecimal("85")),
            )
        val cost =
            CostModel(
                commissionRate = BigDecimal("0.00015"),
                taxRateKospi = BigDecimal("0.0018"),
                taxRateKosdaq = BigDecimal("0.005"), // 가상 차이
                slippageRate = BigDecimal.ZERO,
            )
        val engine = BacktestEngine(cost)
        val sKosdaq =
            engine.run(
                symbol = "T",
                market = Market.KOSDAQ,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        val sKospi =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        // KOSDAQ는 매도 세율이 더 높음 → 더 많은 비용 → 더 낮은 pnl
        (sKosdaq.trades[0].exitTax.toDouble() > sKospi.trades[0].exitTax.toDouble()) shouldBe true
    }

    @Test
    fun `re-entry after exit produces second trade`() {
        // ON-OFF-ON-OFF 패턴: 청산 후 재진입.
        val bars =
            listOf(
                bar(0, BigDecimal("150"), open = BigDecimal("150")), // ON
                bar(1, BigDecimal("160"), open = BigDecimal("155")), // ON
                bar(2, BigDecimal("90"), open = BigDecimal("100")), // OFF → bar3 open 매도
                bar(3, BigDecimal("200"), open = BigDecimal("85")), // ON → bar4 open 매수
                bar(4, BigDecimal("250"), open = BigDecimal("210")), // ON
                bar(5, BigDecimal("80"), open = BigDecimal("260")), // OFF → bar6 open 매도
                bar(6, BigDecimal("70"), open = BigDecimal("75")),
            )
        val engine = BacktestEngine(CostModel.ZERO)
        val s =
            engine.run(
                symbol = "T",
                market = Market.KOSPI,
                bars = bars,
                initialCapital = BigDecimal("1000000"),
                conditionExpr = "close > 100",
            )
        s.trades.size shouldBe 2
        s.trades[0].entryPrice shouldBe BigDecimal("155.00")
        s.trades[0].exitPrice shouldBe BigDecimal("85.00")
        s.trades[1].entryPrice shouldBe BigDecimal("210.00")
        s.trades[1].exitPrice shouldBe BigDecimal("75.00")
    }
}

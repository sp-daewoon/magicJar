package com.mj.domain.journal.performance

import com.mj.domain.strategy.backtest.Trade
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * [PerformanceCalculator] 단위 테스트.
 *
 * 핵심 검증:
 * - 빈 trade list → tradeCount=0, finalCapital=initialCapital
 * - 1 winning trade → winRate=1.0, totalReturn>0
 * - equity curve 일별 길이 + KST 타임존 매핑
 * - MONTHLY 집계 — 같은 월 trade 합산
 */
class PerformanceCalculatorTest {
    private val userId = "74683223"
    private val initialCapital = BigDecimal("10000000")

    @Test
    fun `빈 trade list 시 finalCapital = initialCapital`() {
        val (summary, curve) =
            PerformanceCalculator.summarize(
                userId = userId,
                fromDate = LocalDate.of(2026, 1, 1),
                toDate = LocalDate.of(2026, 1, 31),
                initialCapital = initialCapital,
                trades = emptyList(),
            )
        assertEquals(0, summary.tradeCount)
        assertEquals(initialCapital, summary.finalCapital)
        // 1일~31일 = 31 days curve
        assertEquals(31, curve.size)
        // 모든 equity = initialCapital (거래 없음)
        assertTrue(curve.all { it.equity.compareTo(initialCapital) == 0 })
    }

    @Test
    fun `1개 winning trade — tradeCount=1·승률=1`() {
        val trade =
            Trade.create(
                symbol = "005930",
                entryDate = LocalDate.of(2026, 1, 5).atStartOfDay().toInstant(ZoneOffset.UTC),
                exitDate = LocalDate.of(2026, 1, 10).atStartOfDay().toInstant(ZoneOffset.UTC),
                entryPrice = BigDecimal("70000"),
                exitPrice = BigDecimal("72000"),
                shares = 10,
                entryCommission = BigDecimal("100"),
                exitCommission = BigDecimal("100"),
                exitTax = BigDecimal("130"),
                reason = Trade.ExitReason.SIGNAL_EXIT,
            )
        val (summary, _) =
            PerformanceCalculator.summarize(
                userId = userId,
                fromDate = LocalDate.of(2026, 1, 1),
                toDate = LocalDate.of(2026, 1, 31),
                initialCapital = initialCapital,
                trades = listOf(trade),
            )
        assertEquals(1, summary.tradeCount)
        assertEquals(BigDecimal("1.000000"), summary.metrics.winRate)
        assertTrue(summary.metrics.totalReturn.signum() > 0)
        // pnl = (72000-70000)*10 - 100 - 100 - 130 = 19670
        assertEquals(BigDecimal("19670.00"), trade.pnl)
    }

    @Test
    fun `MONTHLY 집계 — 같은 월 trade 합산`() {
        val t1 = makeTrade("005930", LocalDate.of(2026, 5, 5), LocalDate.of(2026, 5, 10), 100000)
        val t2 = makeTrade("000660", LocalDate.of(2026, 5, 15), LocalDate.of(2026, 5, 20), 50000)
        val t3 = makeTrade("005930", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), -30000)
        val entries =
            PerformanceCalculator.aggregateByPeriod(
                period = PerformancePeriod.MONTHLY,
                fromDate = LocalDate.of(2026, 5, 1),
                toDate = LocalDate.of(2026, 6, 30),
                initialCapital = initialCapital,
                trades = listOf(t1, t2, t3),
            )
        assertEquals(2, entries.size)
        val may = entries.find { it.periodLabel == "2026-05" }!!
        val jun = entries.find { it.periodLabel == "2026-06" }!!
        assertEquals(2, may.tradeCount)
        assertEquals(BigDecimal("150000.00"), may.pnl)
        assertEquals(1, jun.tradeCount)
        assertEquals(BigDecimal("-30000.00"), jun.pnl)
    }

    @Test
    fun `ALL_TIME 집계 — 전체 단일 항목`() {
        val t1 = makeTrade("005930", LocalDate.of(2026, 1, 5), LocalDate.of(2026, 1, 10), 100000)
        val t2 = makeTrade("005930", LocalDate.of(2026, 6, 1), LocalDate.of(2026, 6, 5), 200000)
        val entries =
            PerformanceCalculator.aggregateByPeriod(
                period = PerformancePeriod.ALL_TIME,
                fromDate = LocalDate.of(2026, 1, 1),
                toDate = LocalDate.of(2026, 12, 31),
                initialCapital = initialCapital,
                trades = listOf(t1, t2),
            )
        assertEquals(1, entries.size)
        val all = entries.first()
        assertEquals(2, all.tradeCount)
        assertEquals(BigDecimal("300000.00"), all.pnl)
    }

    private fun makeTrade(
        symbol: String,
        entry: LocalDate,
        exit: LocalDate,
        netPnl: Int,
    ): Trade {
        // pnl 직접 만들기 위해 (exit - entry) * 1주 = netPnl + 비용 0
        val entryPrice = BigDecimal("100000")
        val exitPrice = entryPrice.add(BigDecimal(netPnl))
        return Trade.create(
            symbol = symbol,
            entryDate = entry.atStartOfDay().toInstant(ZoneOffset.UTC),
            exitDate = exit.atStartOfDay().toInstant(ZoneOffset.UTC),
            entryPrice = entryPrice,
            exitPrice = exitPrice,
            shares = 1,
            entryCommission = BigDecimal.ZERO,
            exitCommission = BigDecimal.ZERO,
            exitTax = BigDecimal.ZERO,
            reason = Trade.ExitReason.SIGNAL_EXIT,
        )
    }
}

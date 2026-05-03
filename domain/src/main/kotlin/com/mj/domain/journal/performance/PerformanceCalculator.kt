package com.mj.domain.journal.performance

import com.mj.domain.strategy.backtest.PerformanceMetricsCalculator
import com.mj.domain.strategy.backtest.Trade
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.temporal.IsoFields
import java.util.UUID

/**
 * 실거래 매매일지 기반 성과 계산기.
 *
 * 입력:
 * - userId, dateRange
 * - List<Trade> — 실거래 BUY-SELL 라운드트립 (PerformanceQueryService에서 orders·order_events fill 매핑)
 * - initialCapital — 사용자 설정 또는 첫 거래 직전 평가액
 *
 * 출력:
 * - [PerformanceSummary] — T9 [PerformanceMetricsCalculator] 정합 metrics 포함
 * - 일별 [EquityCurvePoint] 시계열
 * - [PerformancePeriod]별 [PerformancePeriodEntry] 집계
 *
 * 결정론: 동일 입력 → 동일 출력. 난수 사용 안 함.
 *
 * Note: T9 [PerformanceMetricsCalculator]를 wrapping. 실거래 trade는 T9 [Trade] 도메인 재사용
 * (orders·order_events fill → Trade.create 변환은 application layer 책임).
 */
object PerformanceCalculator {
    private val MC = MathContext(12, RoundingMode.HALF_UP)
    private val ZERO = BigDecimal.ZERO
    private val KST = ZoneId.of("Asia/Seoul")

    /**
     * 전체 통계 요약 + equity curve 동시 산출.
     */
    fun summarize(
        userId: UUID,
        fromDate: LocalDate,
        toDate: LocalDate,
        initialCapital: BigDecimal,
        trades: List<Trade>,
    ): Pair<PerformanceSummary, List<EquityCurvePoint>> {
        val equityCurve = buildEquityCurve(fromDate, toDate, initialCapital, trades)
        val equityValues = equityCurve.map { it.equity }
        val years = computeYears(fromDate, toDate)
        val metrics = PerformanceMetricsCalculator.calculate(equityValues, trades, years)
        val finalCapital = equityCurve.lastOrNull()?.equity ?: initialCapital
        val summary =
            PerformanceSummary(
                userId = userId,
                fromDate = fromDate,
                toDate = toDate,
                initialCapital = initialCapital,
                finalCapital = finalCapital,
                metrics = metrics,
                tradeCount = trades.size,
            )
        return summary to equityCurve
    }

    /**
     * 기간별 집계 ([PerformancePeriod]).
     *
     * - DAILY: 일별 P&L (해당일 종결 trade 기준)
     * - WEEKLY: ISO week (월요일 시작)
     * - MONTHLY/YEARLY: 캘린더 월/연
     * - ALL_TIME: 전체 기간 단일 항목
     */
    fun aggregateByPeriod(
        period: PerformancePeriod,
        fromDate: LocalDate,
        toDate: LocalDate,
        initialCapital: BigDecimal,
        trades: List<Trade>,
    ): List<PerformancePeriodEntry> {
        if (period == PerformancePeriod.ALL_TIME) {
            val pnl = trades.fold(ZERO) { acc, t -> acc.add(t.pnl) }
            val ret =
                if (initialCapital.signum() <= 0) ZERO else pnl.divide(initialCapital, MC).setScale(6, RoundingMode.HALF_UP)
            return listOf(
                PerformancePeriodEntry(
                    period = period,
                    periodLabel = "$fromDate~$toDate",
                    startDate = fromDate,
                    endDate = toDate,
                    tradeCount = trades.size,
                    pnl = pnl.setScale(2, RoundingMode.HALF_UP),
                    returnPercent = ret,
                ),
            )
        }
        val grouped =
            trades.groupBy { trade ->
                val exitDate = trade.exitDate.atZone(KST).toLocalDate()
                periodKey(period, exitDate)
            }
        val entries =
            grouped.map { (key, ts) ->
                val (start, end, label) = expandKey(period, key)
                val pnl = ts.fold(ZERO) { acc, t -> acc.add(t.pnl) }
                val ret =
                    if (initialCapital.signum() <= 0) ZERO else pnl.divide(initialCapital, MC).setScale(6, RoundingMode.HALF_UP)
                PerformancePeriodEntry(
                    period = period,
                    periodLabel = label,
                    startDate = start,
                    endDate = end,
                    tradeCount = ts.size,
                    pnl = pnl.setScale(2, RoundingMode.HALF_UP),
                    returnPercent = ret,
                )
            }
        return entries.sortedBy { it.startDate }
    }

    /**
     * 일별 equity curve 생성.
     *
     * 시작 equity = initialCapital. 각 trade exitDate에 pnl 누적.
     * 거래 없는 날은 직전 equity 유지.
     */
    private fun buildEquityCurve(
        fromDate: LocalDate,
        toDate: LocalDate,
        initialCapital: BigDecimal,
        trades: List<Trade>,
    ): List<EquityCurvePoint> {
        if (toDate.isBefore(fromDate)) return emptyList()
        val pnlByDate =
            trades
                .groupBy { it.exitDate.atZone(KST).toLocalDate() }
                .mapValues { (_, ts) -> ts.fold(ZERO) { acc, t -> acc.add(t.pnl) } }
        val out = mutableListOf<EquityCurvePoint>()
        var equity = initialCapital
        var date = fromDate
        out += EquityCurvePoint(date, equity)
        date = date.plusDays(1)
        while (!date.isAfter(toDate)) {
            val pnl = pnlByDate[date] ?: ZERO
            equity = equity.add(pnl)
            out += EquityCurvePoint(date, equity)
            date = date.plusDays(1)
        }
        return out
    }

    private fun computeYears(
        from: LocalDate,
        to: LocalDate,
    ): BigDecimal {
        val days = ChronoUnit.DAYS.between(from, to).coerceAtLeast(1)
        return BigDecimal(days).divide(BigDecimal("365.25"), MC)
    }

    private fun periodKey(
        period: PerformancePeriod,
        date: LocalDate,
    ): String =
        when (period) {
            PerformancePeriod.DAILY -> date.toString()
            PerformancePeriod.WEEKLY -> {
                val year = date.get(IsoFields.WEEK_BASED_YEAR)
                val week = date.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
                "%04d-W%02d".format(year, week)
            }
            PerformancePeriod.MONTHLY -> YearMonth.from(date).toString()
            PerformancePeriod.YEARLY -> date.year.toString()
            PerformancePeriod.ALL_TIME -> "ALL"
        }

    private fun expandKey(
        period: PerformancePeriod,
        key: String,
    ): Triple<LocalDate, LocalDate, String> =
        when (period) {
            PerformancePeriod.DAILY -> {
                val d = LocalDate.parse(key)
                Triple(d, d, key)
            }
            PerformancePeriod.WEEKLY -> {
                val parts = key.split("-W")
                val year = parts[0].toInt()
                val week = parts[1].toInt()
                val monday =
                    LocalDate
                        .now()
                        .withYear(
                            year,
                        ).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, week.toLong())
                        .with(java.time.DayOfWeek.MONDAY)
                Triple(monday, monday.plusDays(6), key)
            }
            PerformancePeriod.MONTHLY -> {
                val ym = YearMonth.parse(key)
                Triple(ym.atDay(1), ym.atEndOfMonth(), key)
            }
            PerformancePeriod.YEARLY -> {
                val year = key.toInt()
                Triple(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31), key)
            }
            PerformancePeriod.ALL_TIME -> {
                Triple(LocalDate.MIN, LocalDate.MAX, key)
            }
        }
}

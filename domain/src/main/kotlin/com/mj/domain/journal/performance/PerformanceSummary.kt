package com.mj.domain.journal.performance

import com.mj.domain.strategy.backtest.PerformanceMetrics
import java.math.BigDecimal
import java.time.LocalDate

/**
 * 실거래 기준 성과 집계 요약.
 *
 * @property userId 사용자 식별자 (multi-tenant)
 * @property fromDate 집계 시작일 (inclusive)
 * @property toDate 집계 종료일 (inclusive)
 * @property initialCapital 시작 자본 (사용자 설정 또는 첫 거래 직전 평가액)
 * @property finalCapital 종료 자본 (마지막 거래 직후 또는 종료일 평가액)
 * @property metrics T9 [PerformanceMetrics] 정합 — totalReturn·CAGR·MDD·Sharpe·승률·profitFactor 등
 * @property tradeCount 종결 거래 수 (BUY-SELL 라운드트립)
 */
data class PerformanceSummary(
    val userId: java.util.UUID,
    val fromDate: LocalDate,
    val toDate: LocalDate,
    val initialCapital: BigDecimal,
    val finalCapital: BigDecimal,
    val metrics: PerformanceMetrics,
    val tradeCount: Int,
)

/**
 * 기간별 성과 집계 단일 항목.
 */
data class PerformancePeriodEntry(
    val period: PerformancePeriod,
    val periodLabel: String,
    val startDate: LocalDate,
    val endDate: LocalDate,
    val tradeCount: Int,
    val pnl: BigDecimal,
    val returnPercent: BigDecimal,
)

/**
 * Equity curve 데이터 포인트 (일별 자산 곡선).
 */
data class EquityCurvePoint(
    val date: LocalDate,
    val equity: BigDecimal,
)

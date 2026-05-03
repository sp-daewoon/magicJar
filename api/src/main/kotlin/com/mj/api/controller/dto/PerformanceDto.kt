package com.mj.api.controller.dto

import com.mj.domain.journal.performance.EquityCurvePoint
import com.mj.domain.journal.performance.PerformancePeriod
import com.mj.domain.journal.performance.PerformancePeriodEntry
import com.mj.domain.journal.performance.PerformanceSummary
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

@Schema(description = "성과 통계 메트릭 (T9 PerformanceMetrics 정합)")
data class PerformanceMetricsDto(
    @Schema(description = "누적 수익률 (예: 0.123 = +12.3%)", example = "0.123456")
    val totalReturn: BigDecimal,
    @Schema(description = "연환산 복리 수익률 CAGR", example = "0.085000")
    val cagr: BigDecimal,
    @Schema(description = "Maximum Drawdown (음수, 예: -0.082 = -8.2%)", example = "-0.082000")
    val mdd: BigDecimal,
    @Schema(description = "샤프 비율 (일별 수익률 기준 √252 연환산)", example = "1.250000")
    val sharpe: BigDecimal,
    @Schema(description = "승률 (예: 0.55 = 55%)", example = "0.550000")
    val winRate: BigDecimal,
    @Schema(description = "Profit Factor (총 이익 / |총 손실|, ≥1.0이 흑자)", example = "1.850000")
    val profitFactor: BigDecimal,
    @Schema(description = "최대 연속 손실 trade 수", example = "3")
    val maxConsecutiveLosses: Int,
    @Schema(description = "평균 이익 (원)", example = "150000.00")
    val avgWin: BigDecimal,
    @Schema(description = "평균 손실 (원, 절대값)", example = "80000.00")
    val avgLoss: BigDecimal,
    @Schema(description = "종결 거래 수", example = "42")
    val tradeCount: Int,
)

@Schema(description = "성과 요약 응답 (메트릭 + equity curve)")
data class PerformanceSummaryResponse(
    @Schema(description = "사용자 식별자", example = "00000000-0000-0000-0000-000000000001")
    val userId: UUID,
    @Schema(description = "조회 시작일", example = "2026-01-01")
    val fromDate: LocalDate,
    @Schema(description = "조회 종료일", example = "2026-12-31")
    val toDate: LocalDate,
    @Schema(description = "시작 자본 (원)", example = "10000000.00")
    val initialCapital: BigDecimal,
    @Schema(description = "종료 자본 (원)", example = "11250000.00")
    val finalCapital: BigDecimal,
    @Schema(description = "통계 메트릭")
    val metrics: PerformanceMetricsDto,
    @Schema(description = "일별 equity curve")
    val equityCurve: List<EquityCurvePointDto>,
)

@Schema(description = "Equity curve 단일 데이터 포인트")
data class EquityCurvePointDto(
    @Schema(description = "날짜 (KST)", example = "2026-05-02")
    val date: LocalDate,
    @Schema(description = "해당 일자 마감 자산 평가액 (원)", example = "10250000.00")
    val equity: BigDecimal,
)

@Schema(description = "기간별 P&L 집계 단일 항목")
data class PerformancePeriodEntryDto(
    @Schema(description = "집계 단위", example = "MONTHLY")
    val period: PerformancePeriod,
    @Schema(description = "기간 라벨 (예: '2026-05', '2026-W18', '2026-05-02')", example = "2026-05")
    val periodLabel: String,
    @Schema(description = "기간 시작일", example = "2026-05-01")
    val startDate: LocalDate,
    @Schema(description = "기간 종료일", example = "2026-05-31")
    val endDate: LocalDate,
    @Schema(description = "해당 기간 종결 거래 수", example = "8")
    val tradeCount: Int,
    @Schema(description = "해당 기간 P&L (원)", example = "350000.00")
    val pnl: BigDecimal,
    @Schema(description = "해당 기간 수익률 (initialCapital 대비)", example = "0.035000")
    val returnPercent: BigDecimal,
)

// ──────────────────────────────────────────────────────────
// Mapper functions
// ──────────────────────────────────────────────────────────

fun com.mj.domain.strategy.backtest.PerformanceMetrics.toDto(): PerformanceMetricsDto =
    PerformanceMetricsDto(
        totalReturn = totalReturn,
        cagr = cagr,
        mdd = mdd,
        sharpe = sharpe,
        winRate = winRate,
        profitFactor = profitFactor,
        maxConsecutiveLosses = maxConsecutiveLosses,
        avgWin = avgWin,
        avgLoss = avgLoss,
        tradeCount = tradeCount,
    )

fun PerformanceSummary.toResponse(curve: List<EquityCurvePoint>): PerformanceSummaryResponse =
    PerformanceSummaryResponse(
        userId = userId,
        fromDate = fromDate,
        toDate = toDate,
        initialCapital = initialCapital,
        finalCapital = finalCapital,
        metrics = metrics.toDto(),
        equityCurve = curve.map { it.toDto() },
    )

fun EquityCurvePoint.toDto(): EquityCurvePointDto =
    EquityCurvePointDto(
        date = date,
        equity = equity,
    )

fun PerformancePeriodEntry.toDto(): PerformancePeriodEntryDto =
    PerformancePeriodEntryDto(
        period = period,
        periodLabel = periodLabel,
        startDate = startDate,
        endDate = endDate,
        tradeCount = tradeCount,
        pnl = pnl,
        returnPercent = returnPercent,
    )

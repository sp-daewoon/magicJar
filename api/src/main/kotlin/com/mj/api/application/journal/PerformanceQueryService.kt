package com.mj.api.application.journal

import com.mj.domain.journal.performance.EquityCurvePoint
import com.mj.domain.journal.performance.PerformanceCalculator
import com.mj.domain.journal.performance.PerformancePeriod
import com.mj.domain.journal.performance.PerformancePeriodEntry
import com.mj.domain.journal.performance.PerformanceSummary
import com.mj.domain.journal.performance.port.RealizedTradeRepository
import org.springframework.stereotype.Service
import java.time.LocalDate
import java.util.UUID

/**
 * 실거래 매매일지 기반 성과 조회 application service.
 *
 * - orders + order_events에서 fill 거래 매핑은 [RealizedTradeRepository] adapter 책임
 * - 통계 계산은 도메인 [PerformanceCalculator] 책임 (T9 [PerformanceMetricsCalculator] 정합)
 *
 * Multi-tenant: 모든 메서드는 userId 필수 (ADR 0043).
 */
@Service
class PerformanceQueryService(
    private val realizedTradeRepository: RealizedTradeRepository,
) {
    /**
     * 전체 통계 요약 + equity curve 동시 산출.
     */
    fun summary(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): Pair<PerformanceSummary, List<EquityCurvePoint>> {
        val initialCapital = realizedTradeRepository.findInitialCapital(userId)
        val trades = realizedTradeRepository.findClosedTrades(userId, from, to)
        return PerformanceCalculator.summarize(userId, from, to, initialCapital, trades)
    }

    /**
     * Equity curve만 추출 (차트용).
     */
    fun equityCurve(
        userId: UUID,
        from: LocalDate,
        to: LocalDate,
    ): List<EquityCurvePoint> = summary(userId, from, to).second

    /**
     * 기간별 집계 (DAILY·WEEKLY·MONTHLY·YEARLY·ALL_TIME).
     */
    fun byPeriod(
        userId: UUID,
        period: PerformancePeriod,
        from: LocalDate,
        to: LocalDate,
    ): List<PerformancePeriodEntry> {
        val initialCapital = realizedTradeRepository.findInitialCapital(userId)
        val trades = realizedTradeRepository.findClosedTrades(userId, from, to)
        return PerformanceCalculator.aggregateByPeriod(period, from, to, initialCapital, trades)
    }
}

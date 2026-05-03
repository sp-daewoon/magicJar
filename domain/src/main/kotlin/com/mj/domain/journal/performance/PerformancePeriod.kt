package com.mj.domain.journal.performance

/**
 * 성과 통계 집계 기간.
 *
 * - DAILY: 일별 P&L
 * - WEEKLY: 주별 (월요일 시작 기준)
 * - MONTHLY: 월별
 * - YEARLY: 연별
 * - ALL_TIME: 전체 기간 단일 집계
 */
enum class PerformancePeriod {
    DAILY,
    WEEKLY,
    MONTHLY,
    YEARLY,
    ALL_TIME,
}

package kr.spdaewoon.magicjar.api.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * strategy_daily_performance Spring Data JPA Repository — 헌법 §6 (ADR 0051) abstract fun only LOCK.
 *
 * body 가진 fun 0건 — Repository interface default fun 함정(query inference No property 부팅 실패) 방지.
 * 요약 집계·신뢰도·레짐 그룹핑 등 로직은 [kr.spdaewoon.magicjar.api.service.performance.StrategyPerformanceService] 에.
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합 (pnl_daily V30 형제).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6 읽기 전용)
 */
interface StrategyDailyPerformanceJpaRepository : JpaRepository<StrategyDailyPerformanceJpaEntity, Long> {
    /** 전략×기간 일자별 성과 행 (히스토리 — 최신일 우선 trade_date DESC). */
    fun findByStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(
        strategyId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<StrategyDailyPerformanceJpaEntity>

    /** 전략 누적 전체 행 (요약·신뢰도·레짐 그룹핑 소스 — userId 격리). */
    fun findByUserIdAndStrategyId(
        userId: String,
        strategyId: Long,
    ): List<StrategyDailyPerformanceJpaEntity>
}

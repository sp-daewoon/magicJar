package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * batch 모듈 strategy_daily_performance Repository (Flyway V85) — 헌법 §6 abstract fun only LOCK.
 *
 * DailyPerformanceJob 전략 왕복 집계 멱등 UPSERT용.
 * 멱등 UPSERT(UQ user_id, strategy_id, trade_date)는 DELETE + INSERT 로 caller(DailyPerformanceJob)에서 수행
 * (BatchPnlDailyJpaRepository·PerformanceRepositoryAdapter 패턴 정합).
 *
 * // AI 호출 0 / KIS 호출 0 (ADR 0126 §3-3·§3-6)
 */
interface BatchStrategyDailyPerformanceJpaRepository : JpaRepository<BatchStrategyDailyPerformanceJpaEntity, Long> {
    fun findByUserIdAndStrategyIdAndTradeDate(
        userId: String,
        strategyId: Long,
        tradeDate: LocalDate,
    ): BatchStrategyDailyPerformanceJpaEntity?
}

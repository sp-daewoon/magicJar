package kr.spdaewoon.magicjar.api.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import java.time.LocalDate

/**
 * strategy_daily_note Spring Data JPA Repository — 헌법 §6 (ADR 0051) abstract fun only LOCK.
 *
 * body 가진 fun 0건 — UPSERT(있으면 수정) 로직은
 * [kr.spdaewoon.magicjar.api.service.performance.StrategyPerformanceService] 에.
 *
 * userId: String — user_account.user_id VARCHAR(40) 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6 메모 CRUD)
 */
interface StrategyDailyNoteJpaRepository : JpaRepository<StrategyDailyNoteJpaEntity, Long> {
    /** 전략×기간 메모 타임라인 (최신일 우선 trade_date DESC). */
    fun findByUserIdAndStrategyIdAndTradeDateBetweenOrderByTradeDateDesc(
        userId: String,
        strategyId: Long,
        from: LocalDate,
        to: LocalDate,
    ): List<StrategyDailyNoteJpaEntity>

    /** 전략×일자 단건 (메모 UPSERT 시 존재 여부 판정). */
    fun findByUserIdAndStrategyIdAndTradeDate(
        userId: String,
        strategyId: Long,
        tradeDate: LocalDate,
    ): StrategyDailyNoteJpaEntity?
}

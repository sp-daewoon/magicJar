package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * batch 모듈 market_index_daily Repository (Flyway V86) — 헌법 §6 abstract fun only LOCK.
 *
 * DailyPerformanceJob 지수 일봉 롤업 UPSERT + 전략 일자행 벤치마크 조회용.
 * 멱등 UPSERT 는 DELETE + INSERT 로 caller(BatchMarketIndexDailyRollup) 에서 수행
 * (PerformanceRepositoryAdapter·api MarketIndexDailyRollupService 패턴 정합).
 *
 * // AI 호출 0 / KIS 호출 0 (ADR 0126 §3-4·§3-6)
 */
interface BatchMarketIndexDailyJpaRepository : JpaRepository<BatchMarketIndexDailyJpaEntity, Long> {
    fun findByIndexCodeAndTradeDate(
        indexCode: String,
        tradeDate: LocalDate,
    ): BatchMarketIndexDailyJpaEntity?

    /**
     * change_pct 산정용 — 대상일 직전(이전 영업일) 동일 지수 롤업 1건 (전일 close 참조).
     * 직전 행이 없으면 null → 산식이 (close − open)/open 으로 fallback (spec §3 V86 change_pct).
     */
    @Query(
        value = """
            SELECT *
            FROM market_index_daily
            WHERE index_code = :indexCode
              AND trade_date < :tradeDate
            ORDER BY trade_date DESC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findMostRecentBefore(
        @Param("indexCode") indexCode: String,
        @Param("tradeDate") tradeDate: LocalDate,
    ): BatchMarketIndexDailyJpaEntity?
}

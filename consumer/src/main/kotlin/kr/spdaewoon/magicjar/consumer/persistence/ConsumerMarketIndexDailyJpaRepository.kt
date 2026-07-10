package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * market_index_daily 읽기 전용 Repository (consumer) — Flyway V86 정합.
 *
 * [RegimeEntryGate]가 최신 영업일 지수 레짐을 읽는 단일 경로. 헌법 §6 — abstract fun only(default fun 0).
 */
interface ConsumerMarketIndexDailyJpaRepository : JpaRepository<ConsumerMarketIndexDailyJpaEntity, Long> {
    /**
     * 지정 지수의 **가장 최근 영업일 1행** — 레짐 판정 source.
     *
     * market_index_daily 는 batch EOD 롤업(매일 00:30 KST)이 채우므로, 장중에는 "전일 EOD 레짐"이 최신값이다
     * (장중 라이브 레짐 분류기는 현재 부재 — 본 폴백이 권위). regime 컬럼이 null(지수 부재일)이면 gate 가 무시한다.
     */
    @Query(
        value = """
            SELECT *
            FROM market_index_daily
            WHERE index_code = :indexCode
            ORDER BY trade_date DESC
            LIMIT 1
        """,
        nativeQuery = true,
    )
    fun findLatestByIndexCode(
        @Param("indexCode") indexCode: String,
    ): ConsumerMarketIndexDailyJpaEntity?
}

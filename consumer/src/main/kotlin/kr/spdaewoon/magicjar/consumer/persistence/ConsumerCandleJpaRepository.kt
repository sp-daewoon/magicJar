package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * candle Spring Data JPA 리포지터리 — consumer 모듈.
 */
interface ConsumerCandleJpaRepository : JpaRepository<ConsumerCandleJpaEntity, Long> {
    fun countByCodeAndInterval(
        code: String,
        interval: String,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: Collection<String>,
    ): Long

    /**
     * 단건 idempotent INSERT (ON CONFLICT DO NOTHING).
     */
    @Modifying
    @Query(
        nativeQuery = true,
        value = """
            INSERT INTO candle (code, interval, ts, open_raw, high_raw, low_raw, close_raw, volume, source)
            VALUES (:code, :interval, :ts, :openRaw, :highRaw, :lowRaw, :closeRaw, :volume, :source)
            ON CONFLICT (code, interval, ts) DO NOTHING
        """,
    )
    fun insertIgnore(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("ts") ts: java.time.Instant,
        @Param("openRaw") openRaw: java.math.BigDecimal?,
        @Param("highRaw") highRaw: java.math.BigDecimal?,
        @Param("lowRaw") lowRaw: java.math.BigDecimal?,
        @Param("closeRaw") closeRaw: java.math.BigDecimal?,
        @Param("volume") volume: Long?,
        @Param("source") source: String,
    ): Int
}

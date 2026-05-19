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
     * 종목·interval 전체 candle 조회 (ts ASC).
     * Spring Data query inference — body 없음 (헌법 §6 JPA default fun 함정 회피).
     * ADR 0062 §9 AggregateBackfillRunner 1m 전량 fetch 용.
     */
    fun findByCodeAndIntervalOrderByTsAsc(
        code: String,
        interval: String,
    ): List<ConsumerCandleJpaEntity>

    /**
     * 종목·interval 의 마지막 ts (Instant) — row 0 건이면 null.
     * hotfix 2026-05-15: CandleBootstrapRunner stale 검출 용.
     */
    @Query(
        "SELECT MAX(c.ts) FROM ConsumerCandleJpaEntity c WHERE c.code = :code AND c.interval = :interval",
    )
    fun findMaxTsByCodeAndInterval(
        @Param("code") code: String,
        @Param("interval") interval: String,
    ): java.time.Instant?

    /**
     * 종목·interval·범위 내 ts 만 ASC 조회 — ADR 0086 §2-2 add-only.
     * T-CC Phase 2-A CandleGapDetector actual sequence diff 산출 용.
     */
    @Query(
        "SELECT c.ts FROM ConsumerCandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "AND c.ts >= :fromTs AND c.ts <= :toTs " +
            "ORDER BY c.ts ASC",
    )
    fun findTsByCodeAndIntervalAndTsBetween(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("fromTs") fromTs: java.time.Instant,
        @Param("toTs") toTs: java.time.Instant,
    ): List<java.time.Instant>

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

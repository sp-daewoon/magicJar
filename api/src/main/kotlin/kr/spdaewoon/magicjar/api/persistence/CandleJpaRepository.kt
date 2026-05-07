package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * candle Spring Data JPA 리포지터리 (ADR 0019).
 * batch INSERT는 nativeQuery ON CONFLICT DO NOTHING 사용.
 */
interface CandleJpaRepository : JpaRepository<CandleJpaEntity, Long> {
    fun existsByCodeAndIntervalAndTs(
        code: String,
        interval: String,
        ts: java.time.Instant,
    ): Boolean

    fun countByCodeAndInterval(
        code: String,
        interval: String,
    ): Long

    fun countByCodeAndIntervalIn(
        code: String,
        intervals: Collection<String>,
    ): Long

    /**
     * 캔들 시계열 페이지네이션 조회 — before 이전 ts DESC LIMIT limit (CandleQueryService 용).
     * 응답은 ASC 변환은 service 레이어 책임.
     */
    @Query(
        "SELECT c FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval AND c.ts < :before " +
            "ORDER BY c.ts DESC",
    )
    fun findByCodeAndIntervalAndTsBefore(
        @Param("code") code: String,
        @Param("interval") interval: String,
        @Param("before") before: Instant,
        pageable: Pageable,
    ): List<CandleJpaEntity>

    /**
     * before 없이 최신순 조회.
     */
    @Query(
        "SELECT c FROM CandleJpaEntity c " +
            "WHERE c.code = :code AND c.interval = :interval " +
            "ORDER BY c.ts DESC",
    )
    fun findByCodeAndIntervalLatest(
        @Param("code") code: String,
        @Param("interval") interval: String,
        pageable: Pageable,
    ): List<CandleJpaEntity>

    /**
     * 단건 idempotent INSERT (ON CONFLICT DO NOTHING).
     * batch INSERT가 필요하면 saveAll + flush 패턴 사용.
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

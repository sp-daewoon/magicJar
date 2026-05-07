package kr.spdaewoon.magicjar.api.persistence

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDate

/**
 * instrument Spring Data JPA Repository.
 * PR-11b 보강: active 조회·markDelisted·pg_trgm fallback 검색 메서드 추가.
 * PR-21b 보강: findActiveTradeable·findByMarketCapDescLimit·findByIsKospi200True 추가.
 */
interface InstrumentJpaRepository : JpaRepository<InstrumentJpaEntity, String> {
    fun existsByCode(code: String): Boolean

    /** 활성 종목 전체 조회 (Redis 인덱스 rebuild 용도). */
    fun findByActiveTrue(): List<InstrumentJpaEntity>

    /** 특정 market의 활성 종목 조회. */
    fun findByActiveTrueAndMarket(market: String): List<InstrumentJpaEntity>

    /** 종목코드 목록으로 활성 종목 조회 (sync diff 용도). */
    fun findByActiveTrueAndCodeIn(codes: Collection<String>): List<InstrumentJpaEntity>

    /**
     * 상장폐지 처리 — DELETE 금지 (ADR 0017 §2-2).
     * active=false + delisted_at=today + updated_at=now UPDATE.
     */
    @Modifying
    @Query(
        """
        UPDATE InstrumentJpaEntity i
        SET i.active = false, i.delistedAt = :delistedAt, i.updatedAt = CURRENT_TIMESTAMP
        WHERE i.code = :code
        """,
    )
    fun markDelisted(
        @Param("code") code: String,
        @Param("delistedAt") delistedAt: LocalDate,
    )

    /**
     * JPQL fallback 검색 (pg_trgm 미가용 시 LIKE 기반).
     * name·nameNorm·code prefix 부분 일치.
     * 정규화: 공백·점 제거 + lowercase는 InstrumentSearchService에서 전처리.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
        AND (
            LOWER(i.name) LIKE LOWER(CONCAT('%', :query, '%'))
            OR i.code LIKE CONCAT(:query, '%')
            OR (i.nameNorm IS NOT NULL AND LOWER(i.nameNorm) LIKE LOWER(CONCAT('%', :query, '%')))
        )
        AND (:market IS NULL OR i.market = :market)
        ORDER BY
            CASE WHEN LOWER(i.code) = LOWER(:query) THEN 0
                 WHEN i.code LIKE CONCAT(:query, '%') THEN 1
                 WHEN LOWER(i.name) = LOWER(:query) THEN 2
                 WHEN LOWER(i.name) LIKE LOWER(CONCAT(:query, '%')) THEN 3
                 ELSE 4 END,
            i.name ASC
        """,
    )
    fun searchByNameOrCode(
        @Param("query") query: String,
        @Param("market") market: String?,
        pageable: Pageable,
    ): List<InstrumentJpaEntity>

    // ==========================================================================
    // PR-21b 신규 — T-SC·T-IND screening 지원
    // ==========================================================================

    /**
     * 매매 가능한 활성 종목 (PR-21b).
     * active=true AND trading_halt=false AND liquidation=false.
     * T-SC universe 1차 필터.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
          AND i.tradingHalt = false
          AND i.liquidation = false
        ORDER BY i.code ASC
        """,
    )
    fun findActiveTradeable(): List<InstrumentJpaEntity>

    /**
     * market별 활성 종목 수 집계 (PR-21b).
     * T-MS1 시장 통계용.
     */
    @Query(
        """
        SELECT i.market, COUNT(i) FROM InstrumentJpaEntity i
        WHERE i.active = true
        GROUP BY i.market
        """,
    )
    fun countByMarket(): List<Array<Any>>

    /**
     * 시가총액 내림차순 종목 목록 (PR-21b).
     * market_cap_prev DESC NULLS LAST.
     * T-SC 대형주 screening.
     */
    @Query(
        """
        SELECT i FROM InstrumentJpaEntity i
        WHERE i.active = true
          AND i.marketCapPrev IS NOT NULL
        ORDER BY i.marketCapPrev DESC
        """,
    )
    fun findByMarketCapDescLimit(pageable: Pageable): List<InstrumentJpaEntity>

    /**
     * KOSPI200 편입 종목 목록 (PR-21b).
     * T-SC 기준 universe.
     */
    fun findByActiveTrueAndIsKospi200True(): List<InstrumentJpaEntity>
}

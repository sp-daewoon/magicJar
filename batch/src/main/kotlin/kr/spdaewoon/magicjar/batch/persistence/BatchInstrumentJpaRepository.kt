package kr.spdaewoon.magicjar.batch.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.transaction.annotation.Transactional

/**
 * instrument Spring Data JPA Repository (batch 모듈 — T-IM1 sync 전용).
 */
interface BatchInstrumentJpaRepository : JpaRepository<BatchInstrumentJpaEntity, String> {
    /**
     * 활성 종목 전체 조회 (sync diff 기준).
     */
    fun findAllByActiveTrue(): List<BatchInstrumentJpaEntity>

    /**
     * 코드로 단건 조회.
     */
    fun findByCode(code: String): BatchInstrumentJpaEntity?

    /**
     * instrument row 수 조회 (시드 fallback 판단용).
     */
    fun countByActiveTrue(): Long

    /**
     * ON CONFLICT upsert — 종목 마스터 INSERT 또는 UPDATE.
     * active=true 유지 (신규 상장은 INSERT, 기존은 UPDATE).
     */
    @Modifying
    @Transactional
    @Query(
        value = """
            INSERT INTO instrument (code, isin, name, name_en, market, type, sector, active, created_at, updated_at)
            VALUES (:code, :isin, :name, :nameEn, :market, :type, :sector, true, now(), now())
            ON CONFLICT (code) DO UPDATE SET
                name = EXCLUDED.name,
                name_en = EXCLUDED.name_en,
                market = EXCLUDED.market,
                type = EXCLUDED.type,
                sector = EXCLUDED.sector,
                active = true,
                updated_at = now()
        """,
        nativeQuery = true,
    )
    fun upsert(
        code: String,
        isin: String?,
        name: String,
        nameEn: String?,
        market: String,
        type: String,
        sector: String?,
    )

    /**
     * 상장폐지 처리 — delisted_at UPDATE, active=false.
     * DELETE 금지 (ADR 0017 §2-2 — 생존자 편향 차단).
     */
    @Modifying
    @Transactional
    @Query(
        value = "UPDATE instrument SET delisted_at = CAST(:delistedAt AS date), active = false, updated_at = now() WHERE code = :code",
        nativeQuery = true,
    )
    fun markDelisted(
        code: String,
        delistedAt: String,
    )
}

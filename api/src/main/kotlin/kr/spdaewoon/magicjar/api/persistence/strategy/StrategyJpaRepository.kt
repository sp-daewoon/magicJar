package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 전략 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [StrategyJpaAdapter] 또는 [StrategyLookupJpaAdapter] private fun 위치.
 *
 * V14 추가: status 4단 쿼리 (ADR 0066 §2-6 F-1).
 */
interface StrategyJpaRepository : JpaRepository<StrategyJpaEntity, Long> {
    fun findAllByUserIdAndStatusNot(
        userId: Long,
        status: String,
        pageable: Pageable,
    ): Page<StrategyJpaEntity>

    fun findByIdAndStatusNot(
        id: Long,
        status: String,
    ): StrategyJpaEntity?

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s " +
            "SET s.disabledReason = :reason, s.lastDisabledAt = CURRENT_TIMESTAMP, s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun updateDisabledReason(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s SET s.disabledReason = NULL, s.lastDisabledAt = NULL, s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun clearDisabledReason(
        @Param("id") id: Long,
    ): Int

    /**
     * status 전환 — JPQL UPDATE (V14 status + timestamp 동기).
     * ADR 0066 §2-6 F-1 transition 매트릭스 정합.
     */
    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s " +
            "SET s.status = :status, s.updatedAt = CURRENT_TIMESTAMP " +
            "WHERE s.id = :id",
    )
    fun updateStatus(
        @Param("id") id: Long,
        @Param("status") status: String,
    ): Int

    /**
     * ACTIVE 전략 중 codes JSONB에 특정 code를 포함하고 intervals JSONB에 특정 interval을 포함하는 목록 조회.
     * native query — PostgreSQL jsonb @> 연산자 사용.
     */
    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE status = 'ACTIVE' " +
                "AND (codes IS NULL OR codes @> CAST(:codeJson AS jsonb)) " +
                "AND (intervals IS NULL OR intervals @> CAST(:intervalJson AS jsonb))",
        nativeQuery = true,
    )
    fun findActiveByCodeAndInterval(
        @Param("codeJson") codeJson: String,
        @Param("intervalJson") intervalJson: String,
    ): List<StrategyJpaEntity>
}

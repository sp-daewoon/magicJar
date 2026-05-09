package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * 전략 Spring Data JPA 리포지터리.
 *
 * ADR 0051 — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [StrategyJpaAdapter] private fun 위치.
 */
interface StrategyJpaRepository : JpaRepository<StrategyJpaEntity, Long> {
    fun findAllByUserId(userId: Long): List<StrategyJpaEntity>

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s SET s.disabledReason = :reason, s.lastDisabledAt = NOW(), s.updatedAt = NOW() " +
            "WHERE s.id = :id",
    )
    fun updateDisabledReason(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int

    @Modifying
    @Query(
        "UPDATE StrategyJpaEntity s SET s.disabledReason = NULL, s.lastDisabledAt = NULL, s.updatedAt = NOW() " +
            "WHERE s.id = :id",
    )
    fun clearDisabledReason(
        @Param("id") id: Long,
    ): Int
}

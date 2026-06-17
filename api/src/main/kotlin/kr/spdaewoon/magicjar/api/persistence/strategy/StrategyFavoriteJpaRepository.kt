package kr.spdaewoon.magicjar.api.persistence.strategy

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.transaction.annotation.Transactional

/**
 * 전략 즐겨찾기 Spring Data JPA 리포지터리 (spec 2026-06-18 §5.2).
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * 멱등 add 등 helper 로직은 [StrategyFavoriteJpaAdapter] private fun 위치.
 */
interface StrategyFavoriteJpaRepository : JpaRepository<StrategyFavoriteJpaEntity, Long> {
    fun findAllByUserId(userId: Long): List<StrategyFavoriteJpaEntity>

    fun existsByUserIdAndStrategyId(
        userId: Long,
        strategyId: Long,
    ): Boolean

    @Transactional
    @Modifying
    fun deleteByUserIdAndStrategyId(
        userId: Long,
        strategyId: Long,
    ): Int
}

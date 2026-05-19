package kr.spdaewoon.magicjar.api.persistence.stomp

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * stomp_event Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * helper는 [StompEventRepositoryAdapter] private fun 위치.
 *
 * ### 쿼리
 * - [findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc]            : channel 무시 backfill
 * - [findByUserIdAndChannelAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc]  : channel filter backfill
 * - [deleteOlderThan]                                                         : retention cron 대상
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
interface StompEventJpaRepository : JpaRepository<StompEventJpaEntity, Long> {
    fun findByUserIdAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        userId: Long,
        createdAt: Instant,
        pageable: Pageable,
    ): List<StompEventJpaEntity>

    fun findByUserIdAndChannelAndCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
        userId: Long,
        channel: String,
        createdAt: Instant,
        pageable: Pageable,
    ): List<StompEventJpaEntity>

    @Modifying
    @Query("DELETE FROM StompEventJpaEntity e WHERE e.createdAt < :cutoff")
    fun deleteOlderThan(
        @Param("cutoff") cutoff: Instant,
    ): Int
}

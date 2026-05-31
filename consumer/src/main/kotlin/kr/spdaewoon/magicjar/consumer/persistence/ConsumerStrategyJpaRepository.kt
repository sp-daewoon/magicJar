package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * strategy repository for consumer-side realtime signal evaluation.
 */
interface ConsumerStrategyJpaRepository : JpaRepository<ConsumerStrategyJpaEntity, Long> {
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
    ): List<ConsumerStrategyJpaEntity>

    @Query(
        value =
            "SELECT * FROM strategy " +
                "WHERE status = 'ACTIVE' " +
                "AND codes IS NOT NULL",
        nativeQuery = true,
    )
    fun findActiveWithExplicitCodes(): List<ConsumerStrategyJpaEntity>

    @Modifying
    @Query(
        value =
            "UPDATE strategy " +
                "SET status = 'DISABLED', " +
                "disabled_reason = :reason, " +
                "last_disabled_at = CURRENT_TIMESTAMP, " +
                "disabled_at = CURRENT_TIMESTAMP, " +
                "updated_at = CURRENT_TIMESTAMP " +
                "WHERE id = :id AND status <> 'DELETED'",
        nativeQuery = true,
    )
    fun autoDisable(
        @Param("id") id: Long,
        @Param("reason") reason: String,
    ): Int
}

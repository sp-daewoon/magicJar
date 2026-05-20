package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
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
}

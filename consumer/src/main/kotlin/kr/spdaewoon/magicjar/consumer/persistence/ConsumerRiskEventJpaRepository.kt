package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * risk_event repository for consumer-side risk persistence.
 */
interface ConsumerRiskEventJpaRepository : JpaRepository<ConsumerRiskEventJpaEntity, Long> {
    @Query(
        """
        SELECT COUNT(e)
        FROM ConsumerRiskEventJpaEntity e
        WHERE e.verdict = 'BLOCKED'
          AND e.ts >= :since
          AND e.signalId IN (
              SELECT s.id
              FROM ConsumerSignalJpaEntity s
              WHERE s.strategyId = :strategyId
          )
        """,
    )
    fun countRecentBlockedByStrategy(
        @Param("strategyId") strategyId: Long,
        @Param("since") since: Instant,
    ): Int
}

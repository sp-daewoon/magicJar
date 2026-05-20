package kr.spdaewoon.magicjar.consumer.persistence

import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * signal repository for consumer-side realtime signal persistence.
 */
interface ConsumerSignalJpaRepository : JpaRepository<ConsumerSignalJpaEntity, Long> {
    fun findByStrategyIdAndCodeAndIntervalAndTs(
        strategyId: Long,
        code: String,
        interval: String,
        ts: Instant,
    ): ConsumerSignalJpaEntity?
}

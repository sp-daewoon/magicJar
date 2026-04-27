package com.mj.batch.infrastructure.persistence.market

import com.mj.domain.common.Interval
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface CandleJpaRepository : JpaRepository<CandleJpa, CandleId> {
    fun findByCodeAndIntervalAndOpenTimeBetween(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<CandleJpa>
}

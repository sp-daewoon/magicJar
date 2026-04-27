package com.mj.domain.market.persistence

import com.mj.domain.common.Interval
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

interface CandleJpaRepository : JpaRepository<CandleJpa, CandleId> {
    fun findByCodeAndIntervalAndOpenTimeBetweenOrderByOpenTimeAsc(
        code: String,
        interval: Interval,
        from: Instant,
        to: Instant,
    ): List<CandleJpa>
}

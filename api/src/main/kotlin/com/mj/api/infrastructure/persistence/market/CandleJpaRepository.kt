package com.mj.api.infrastructure.persistence.market

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

    /**
     * 기준 시각 이하의 가장 최근 일봉(D1) 캔들 반환. Tier-3 last-day-close fallback.
     */
    fun findFirstByCodeAndIntervalAndOpenTimeLessThanEqualOrderByOpenTimeDesc(
        code: String,
        interval: Interval,
        before: Instant,
    ): CandleJpa?
}

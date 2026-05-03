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

    /** 특정 종목의 전체 캔들 조회 (adjusted price 재계산용). openTime ASC 정렬. */
    fun findByCodeAndIntervalOrderByOpenTimeAsc(
        code: String,
        interval: Interval,
    ): List<CandleJpa>
}

package kr.spdaewoon.magicjar.marketdata.persistence

import kr.spdaewoon.magicjar.core.domain.Interval
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

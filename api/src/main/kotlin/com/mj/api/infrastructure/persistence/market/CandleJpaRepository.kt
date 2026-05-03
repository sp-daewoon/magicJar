package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Interval
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
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

    /**
     * infinite scroll: before 이전 candle limit개를 DESC 정렬로 가져온 뒤 ASC 재정렬.
     * T19-T4 — 차트 좌측 과거 데이터 추가 fetch.
     *
     * Pageable.ofSize(limit)으로 limit 제한 (PageRequest 사용).
     * 결과는 openTime DESC이므로 서비스 레이어에서 reversed() 호출 필요.
     */
    @Query(
        "SELECT c FROM CandleJpa c WHERE c.code = :code AND c.interval = :interval " +
            "AND c.openTime < :before ORDER BY c.openTime DESC",
    )
    fun findPageBeforeOrderByOpenTimeDesc(
        code: String,
        interval: Interval,
        before: Instant,
        pageable: Pageable,
    ): List<CandleJpa>
}

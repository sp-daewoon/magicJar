package com.mj.domain.market.cache

import com.mj.domain.common.Interval
import java.time.Instant

/**
 * 분봉 캐시 메타데이터 (candle-data-policy.md §5, ADR 0033).
 *
 * instrument_cache_meta 테이블의 도메인 표현.
 * LRU eviction 정책의 핵심 식별자: (code, interval) 복합 PK.
 * D1(일봉)은 영구 보존이라 이 메타 테이블 대상이 아님 — 분봉(M30·M5·M1)만 사용.
 *
 * @param code             KRX 종목 코드 6자리
 * @param interval         캐시 대상 interval (D1·M30·M5·M1 등)
 * @param lastAccessedAt   마지막 차트 진입 시각 (LRU 기준)
 * @param oldestBar        캐시에 저장된 가장 오래된 candle open_time
 * @param newestBar        캐시에 저장된 가장 최신 candle open_time
 * @param byteEstimate     PostgreSQL 기준 행 수 × 행 크기 추정치 (bytes)
 */
data class CacheMeta(
    val code: String,
    val interval: Interval,
    val lastAccessedAt: Instant,
    val oldestBar: Instant,
    val newestBar: Instant,
    val byteEstimate: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheMeta) return false
        return code == other.code &&
            interval == other.interval &&
            lastAccessedAt == other.lastAccessedAt &&
            oldestBar == other.oldestBar &&
            newestBar == other.newestBar &&
            byteEstimate == other.byteEstimate
    }

    override fun hashCode(): Int = listOf(code, interval, lastAccessedAt, oldestBar, newestBar, byteEstimate).hashCode()
}

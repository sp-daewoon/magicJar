package com.mj.domain.market.cache.port

import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta

/**
 * 분봉 캐시 메타데이터 저장소 포트.
 *
 * LRU eviction 판단·캐시 hit 여부 확인·last_accessed_at 갱신에 사용한다.
 * 구현체: api 모듈 CacheMetaJpaRepository + adapter.
 */
interface CacheMetaRepository {
    /** 종목코드+interval로 단건 조회. 없으면 null. */
    fun findByCodeAndInterval(
        code: String,
        interval: Interval,
    ): CacheMeta?

    /** 저장 (insert or update). */
    fun save(meta: CacheMeta): CacheMeta

    /**
     * last_accessed_at 오름차순으로 조회 (LRU eviction 대상 선별용).
     * @param limit 최대 반환 건수
     */
    fun listByLastAccessedAsc(limit: Int): List<CacheMeta>

    /** 특정 종목+interval 캐시 메타 삭제 (eviction 후 호출). */
    fun deleteByCodeAndInterval(
        code: String,
        interval: Interval,
    )
}

package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Interval
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface CacheMetaJpaRepository : JpaRepository<CacheMetaJpa, CacheMetaId> {
    fun findByCodeAndInterval(
        code: String,
        interval: Interval,
    ): CacheMetaJpa?

    /**
     * last_accessed_at 오름차순 조회 (LRU eviction 대상 선별).
     * @param limit Pageable 대신 JPQL LIMIT 으로 처리 (간단한 쿼리 유지)
     */
    @Query("SELECT m FROM CacheMetaJpa m ORDER BY m.lastAccessedAt ASC LIMIT :limit")
    fun findTopByLastAccessedAtAsc(limit: Int): List<CacheMetaJpa>

    fun deleteByCodeAndInterval(
        code: String,
        interval: Interval,
    )
}

package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta
import com.mj.domain.market.cache.port.CacheMetaRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class CacheMetaRepositoryAdapter(
    private val repository: CacheMetaJpaRepository,
) : CacheMetaRepository {
    @Transactional(readOnly = true)
    override fun findByCodeAndInterval(
        code: String,
        interval: Interval,
    ): CacheMeta? = repository.findByCodeAndInterval(code, interval)?.toDomain()

    @Transactional
    override fun save(meta: CacheMeta): CacheMeta {
        val id = CacheMetaId(meta.code, meta.interval)
        val entity =
            repository.findById(id).orElse(null)?.apply {
                lastAccessedAt = meta.lastAccessedAt
                oldestBar = meta.oldestBar
                newestBar = meta.newestBar
                byteEstimate = meta.byteEstimate
            } ?: meta.toJpa()
        return repository.save(entity).toDomain()
    }

    @Transactional(readOnly = true)
    override fun listByLastAccessedAsc(limit: Int): List<CacheMeta> = repository.findTopByLastAccessedAtAsc(limit).map { it.toDomain() }

    @Transactional
    override fun deleteByCodeAndInterval(
        code: String,
        interval: Interval,
    ) = repository.deleteByCodeAndInterval(code, interval)
}

package com.mj.api.infrastructure.persistence.market

import com.mj.domain.common.Interval
import com.mj.domain.market.cache.CacheMeta
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.IdClass
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.io.Serializable
import java.time.Instant

class CacheMetaId(
    var code: String = "",
    var interval: Interval = Interval.M1,
) : Serializable {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is CacheMetaId) return false
        return code == other.code && interval == other.interval
    }

    override fun hashCode(): Int = listOf(code, interval).hashCode()
}

/**
 * instrument_cache_meta 테이블 JPA 엔티티 (T19-T2).
 *
 * LRU eviction 기준 필드: last_accessed_at.
 * byte_estimate: candle 테이블 row 수 × 행당 약 72 bytes 추정치 (실제 pg_total_relation_size 사용 불가 — 근사).
 */
@Entity
@Table(
    name = "instrument_cache_meta",
    indexes = [
        Index(name = "idx_cache_meta_lru", columnList = "last_accessed_at"),
    ],
)
@IdClass(CacheMetaId::class)
class CacheMetaJpa(
    @Id
    @Column(name = "code", length = 12, nullable = false)
    var code: String,
    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "interval", length = 8, nullable = false)
    var interval: Interval,
    @Column(name = "last_accessed_at", nullable = false)
    var lastAccessedAt: Instant,
    @Column(name = "oldest_bar", nullable = false)
    var oldestBar: Instant,
    @Column(name = "newest_bar", nullable = false)
    var newestBar: Instant,
    @Column(name = "byte_estimate", nullable = false)
    var byteEstimate: Long = 0L,
)

fun CacheMetaJpa.toDomain(): CacheMeta =
    CacheMeta(
        code = code,
        interval = interval,
        lastAccessedAt = lastAccessedAt,
        oldestBar = oldestBar,
        newestBar = newestBar,
        byteEstimate = byteEstimate,
    )

fun CacheMeta.toJpa(): CacheMetaJpa =
    CacheMetaJpa(
        code = code,
        interval = interval,
        lastAccessedAt = lastAccessedAt,
        oldestBar = oldestBar,
        newestBar = newestBar,
        byteEstimate = byteEstimate,
    )

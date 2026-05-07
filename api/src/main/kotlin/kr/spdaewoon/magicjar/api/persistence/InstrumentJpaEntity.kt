package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * instrument JPA 엔티티 (ADR 0017).
 * V3 CHECK 완화로 market='UNKNOWN', type='UNKNOWN' stub row 허용.
 * T-IM1 진입 시 KIS 마스터 동기화로 정식 데이터 갱신.
 */
@Entity
@Table(name = "instrument")
class InstrumentJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "isin", length = 12)
    val isin: String? = null,
    @Column(name = "name", length = 80, nullable = false)
    val name: String,
    @Column(name = "name_en", length = 120)
    val nameEn: String? = null,
    @Column(name = "market", length = 10, nullable = false)
    val market: String,
    @Column(name = "type", length = 20, nullable = false)
    val type: String,
    @Column(name = "sector", length = 40)
    val sector: String? = null,
    @Column(name = "active", nullable = false)
    val active: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "", market = "UNKNOWN", type = "UNKNOWN")
}

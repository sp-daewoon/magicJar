package kr.spdaewoon.magicjar.batch.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * instrument JPA 엔티티 (batch 모듈 — T-IM1 sync 전용).
 * V4 컬럼(name_norm·theme) 포함 — api 모듈 InstrumentJpaEntity와 동일 테이블.
 * api 모듈에 의존하지 않고 batch 자체 엔티티로 독립 관리.
 */
@Entity
@Table(name = "instrument")
class BatchInstrumentJpaEntity(
    @Id
    @Column(name = "code", length = 8, nullable = false)
    val code: String,
    @Column(name = "isin", length = 12)
    val isin: String? = null,
    @Column(name = "name", length = 80, nullable = false)
    var name: String,
    @Column(name = "name_en", length = 120)
    var nameEn: String? = null,
    @Column(name = "market", length = 10, nullable = false)
    var market: String,
    @Column(name = "type", length = 20, nullable = false)
    var type: String,
    @Column(name = "sector", length = 40)
    var sector: String? = null,
    @Column(name = "name_norm", length = 80)
    var nameNorm: String? = null,
    @Column(name = "theme", length = 200)
    var theme: String? = null,
    @Column(name = "active", nullable = false)
    var active: Boolean = true,
    @Column(name = "listed_at")
    val listedAt: LocalDate? = null,
    @Column(name = "delisted_at")
    var delistedAt: LocalDate? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "", market = "UNKNOWN", type = "UNKNOWN")
}

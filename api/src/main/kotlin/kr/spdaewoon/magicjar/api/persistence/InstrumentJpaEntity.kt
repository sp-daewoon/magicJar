package kr.spdaewoon.magicjar.api.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.time.LocalDate

/**
 * instrument JPA 엔티티 (ADR 0017).
 * V3 CHECK 완화로 market='UNKNOWN', type='UNKNOWN' stub row 허용.
 * T-IM1 진입 시 KIS 마스터 동기화로 정식 데이터 갱신.
 *
 * PR-11b 보강: name_norm·theme 컬럼 추가 (Flyway V4 대응 — PR-11a 신규).
 * listed_at·delisted_at 컬럼 추가.
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
    /** 정규화된 종목명 (공백·점 제거 + lowercase) — V4 gin_trgm 인덱스 대상 (PR-11a Flyway V4 추가). */
    @Column(name = "name_norm", length = 80)
    val nameNorm: String? = null,
    /** 테마 (쉼표 구분 — 예: "반도체,스마트폰,IT") — V4 추가 (PR-11a Flyway V4). */
    @Column(name = "theme", length = 200)
    val theme: String? = null,
    @Column(name = "listed_at")
    val listedAt: LocalDate? = null,
    @Column(name = "delisted_at")
    var delistedAt: LocalDate? = null,
    @Column(name = "active", nullable = false)
    var active: Boolean = true,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(code = "", name = "", market = "UNKNOWN", type = "UNKNOWN")
}

package kr.spdaewoon.magicjar.batch.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * batch 모듈용 market_index_daily JPA 엔티티 (Flyway V86 정합) — DailyPerformanceJob 지수 일봉 롤업 UPSERT용.
 *
 * spec `2026-06-16-strategy-daily-performance.md` §3 (V86) · ADR 0126 §3-1.
 * 모듈 경계(ADR 0126 §4): batch 는 api `MarketIndexDailyJpaEntity` 를 못 쓰므로 batch-side 동일 테이블 매핑 신설.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — 룰 기반 레짐 (ADR 0126 §3-4)
 * // KIS 호출 0 — 영속 snapshot 집계 (ADR 0126 §3-6)
 */
@Entity
@Table(name = "market_index_daily")
class BatchMarketIndexDailyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "index_code", nullable = false, length = 16)
    val indexCode: String,
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Column(name = "open", precision = 14, scale = 2)
    val open: BigDecimal? = null,
    @Column(name = "high", precision = 14, scale = 2)
    val high: BigDecimal? = null,
    @Column(name = "low", precision = 14, scale = 2)
    val low: BigDecimal? = null,
    @Column(name = "close", precision = 14, scale = 2)
    val close: BigDecimal? = null,
    @Column(name = "change_pct", precision = 10, scale = 4)
    val changePct: BigDecimal? = null,
    @Column(name = "range_pct", precision = 10, scale = 4)
    val rangePct: BigDecimal? = null,
    @Column(name = "regime", length = 16)
    val regime: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    /** Hibernate 빈 생성자 (proxy 생성용) */
    protected constructor() : this(
        indexCode = "",
        tradeDate = LocalDate.EPOCH,
    )
}

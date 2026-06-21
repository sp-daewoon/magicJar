package kr.spdaewoon.magicjar.consumer.autopilot

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * `autopilot_state` JPA 엔티티 (V91 · account-wide 단일 권위 행 id=1 · spec 2026-06-22 §2-0-A·§2-0-B).
 *
 * autopilot 전용 패키지(`consumer.autopilot`)에 격리 — [MagicjarAutopilotProperties.enabled]=false 면 어떤
 * 코드도 본 엔티티를 읽지 않는다(zero-regression). 도메인 [kr.spdaewoon.magicjar.domain.autopilot.AutopilotState]
 * 와 컬럼을 평탄 미러링하며, 매핑은 [AutopilotStateStore] 책임(헥사고날 — 도메인은 JPA 의존 0).
 *
 * 헌법 §6 — 본 엔티티는 데이터 holder. 쿼리는 [AutopilotStateJpaRepository] abstract fun 만(body 0).
 */
@Entity
@Table(name = "autopilot_state")
class AutopilotStateJpaEntity(
    @Id
    @Column(name = "id")
    val id: Long = SINGLETON_ID,
    @Column(name = "kill_active", nullable = false)
    val killActive: Boolean = false,
    @Column(name = "pool_pct", precision = 6, scale = 3, nullable = false)
    val poolPct: BigDecimal = BigDecimal("5"),
    @Column(name = "per_trade_pct", precision = 6, scale = 3, nullable = false)
    val perTradePct: BigDecimal = BigDecimal("20"),
    @Column(name = "max_positions", nullable = false)
    val maxPositions: Int = 3,
    @Column(name = "daily_loss_pct", precision = 6, scale = 3, nullable = false)
    val dailyLossPct: BigDecimal = BigDecimal("30"),
    @Column(name = "cum_loss_pct", precision = 6, scale = 3, nullable = false)
    val cumLossPct: BigDecimal = BigDecimal("50"),
    @Column(name = "cum_realized_loss", precision = 20, scale = 4, nullable = false)
    val cumRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "daily_realized_loss", precision = 20, scale = 4, nullable = false)
    val dailyRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "last_reset_date", nullable = false)
    val lastResetDate: LocalDate = LocalDate.now(),
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(id = SINGLETON_ID)

    companion object {
        /** account-wide 단일 행 PK (V91 seed id=1). */
        const val SINGLETON_ID: Long = 1L
    }
}

package kr.spdaewoon.magicjar.consumer.autopilot

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * `autopilot_kill_journal` JPA 엔티티 (V92 · spec 2026-06-22 §2-0-D kill 사후분석 저널 · Task 4).
 *
 * kill switch 발동 시점의 손실·한도·격리 대상 스냅샷을 1행 박제하고, resume(해제) 시 동일 행에
 * [resumedAt]·[resumeReason]·[correctedLimitsJson] 을 채운다. **Claude(자율 루프)가 "왜 kill 됐는가"를
 * 분석하는 evidence 원천**이다(사용자 결재 핵심).
 *
 * autopilot 전용 패키지 격리 — MAGICJAR_AUTOPILOT_ENABLED=false(default)면 본 엔티티는 쓰이지 않는다(zero-regression).
 * 헌법 §6 — 본 엔티티는 데이터 holder. 쿼리는 [AutopilotKillJournalRepository] abstract fun 만(body 0).
 */
@Entity
@Table(name = "autopilot_kill_journal")
class AutopilotKillJournalEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long = 0,
    @Column(name = "occurred_at", nullable = false)
    val occurredAt: Instant = Instant.now(),
    @Column(name = "reason", nullable = false)
    val reason: String = "",
    @Column(name = "cum_realized_loss", precision = 20, scale = 4, nullable = false)
    val cumRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "daily_realized_loss", precision = 20, scale = 4, nullable = false)
    val dailyRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "lifetime_realized_loss", precision = 20, scale = 4, nullable = false)
    val lifetimeRealizedLoss: BigDecimal = BigDecimal.ZERO,
    @Column(name = "paused_strategy_count", nullable = false)
    val pausedStrategyCount: Int = 0,
    /** owner=AUTOPILOT 보유 종목 코드 CSV(발동 시점 스냅샷). null = 보유 0. */
    @Column(name = "held_codes")
    val heldCodes: String? = null,
    @Column(name = "pool_pct", precision = 6, scale = 3, nullable = false)
    val poolPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "per_trade_pct", precision = 6, scale = 3, nullable = false)
    val perTradePct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "max_positions", nullable = false)
    val maxPositions: Int = 0,
    @Column(name = "daily_loss_pct", precision = 6, scale = 3, nullable = false)
    val dailyLossPct: BigDecimal = BigDecimal.ZERO,
    @Column(name = "cum_loss_pct", precision = 6, scale = 3, nullable = false)
    val cumLossPct: BigDecimal = BigDecimal.ZERO,
    /** kill 해제 시각. null = 아직 미해제(active kill). */
    @Column(name = "resumed_at")
    val resumedAt: Instant? = null,
    @Column(name = "resume_reason")
    val resumeReason: String? = null,
    @Column(name = "corrected_limits_json")
    val correctedLimitsJson: String? = null,
) {
    protected constructor() : this(id = 0)
}

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
 * autopilot 페이퍼 섀도 트레이드 JPA 엔티티 (개선 G · V93 `autopilot_paper_shadow` 1:1).
 *
 * 실슬롯 starve 된 owner=AUTOPILOT BUY 의 가상 진입 1행. 실 자본/실 주문 0 — 후속 캔들 TP/SL 도달로
 * 가설별 승/패/기대값을 누적하는 학습 장부다(spec 2026-06-22 §학습조건·Phase1 §4).
 *
 * ## 폐기 철학 (spec §3-bis Tier A)
 * autopilot 전용 — MAGICJAR_AUTOPILOT_ENABLED=false 면 어떤 코드도 이 테이블에 쓰지 않는다(zero-regression).
 */
@Entity
@Table(name = "autopilot_paper_shadow")
class AutopilotPaperShadowJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", length = 64, nullable = false)
    val userId: String,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "code", length = 16, nullable = false)
    val code: String,
    @Column(name = "interval", length = 8, nullable = false)
    val interval: String,
    @Column(name = "signal_id")
    val signalId: Long? = null,
    @Column(name = "entry_price", precision = 20, scale = 4, nullable = false)
    val entryPrice: BigDecimal,
    @Column(name = "quantity", nullable = false)
    val quantity: Int,
    @Column(name = "take_profit_pct", precision = 6, scale = 3, nullable = false)
    val takeProfitPct: BigDecimal,
    @Column(name = "stop_loss_pct", precision = 6, scale = 3, nullable = false)
    val stopLossPct: BigDecimal,
    @Column(name = "entry_ts", nullable = false)
    val entryTs: Instant,
    @Column(name = "block_reason", length = 64, nullable = false)
    val blockReason: String,
    @Column(name = "status", length = 16, nullable = false)
    var status: String = STATUS_OPEN,
    @Column(name = "exit_price", precision = 20, scale = 4)
    var exitPrice: BigDecimal? = null,
    @Column(name = "exit_ts")
    var exitTs: Instant? = null,
    @Column(name = "realized_pnl", precision = 20, scale = 4)
    var realizedPnl: BigDecimal? = null,
    @Column(name = "resolved_at")
    var resolvedAt: Instant? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: Instant = Instant.now(),
) {
    companion object {
        const val STATUS_OPEN = "OPEN"
        const val STATUS_WIN = "WIN"
        const val STATUS_LOSS = "LOSS"
    }
}

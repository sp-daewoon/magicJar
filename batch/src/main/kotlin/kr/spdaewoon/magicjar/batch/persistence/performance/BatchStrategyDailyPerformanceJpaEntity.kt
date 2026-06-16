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
 * batch 모듈용 strategy_daily_performance JPA 엔티티 (Flyway V85 정합) — DailyPerformanceJob 전략 왕복 집계 UPSERT용.
 *
 * spec `2026-06-16-strategy-daily-performance.md` §3 (V85) · ADR 0126 §3-1·§3-2.
 * 모듈 경계(ADR 0126 §4): batch 는 api `StrategyDailyPerformanceJpaEntity` 를 못 쓰므로 batch-side 동일 테이블 매핑 신설.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 — 순수 왕복 집계 (ADR 0126 §3-3)
 * // KIS 호출 0 — 영속 order_execution replay (ADR 0126 §3-6)
 */
@Entity
@Table(name = "strategy_daily_performance")
class BatchStrategyDailyPerformanceJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "trade_date", nullable = false)
    val tradeDate: LocalDate,
    @Column(name = "round_trip_count", nullable = false)
    val roundTripCount: Int,
    @Column(name = "win_count", nullable = false)
    val winCount: Int,
    @Column(name = "loss_count", nullable = false)
    val lossCount: Int,
    @Column(name = "realized_pnl", nullable = false, precision = 20, scale = 4)
    val realizedPnl: BigDecimal,
    @Column(name = "total_cost_basis", nullable = false, precision = 20, scale = 4)
    val totalCostBasis: BigDecimal,
    @Column(name = "return_pct", precision = 10, scale = 4)
    val returnPct: BigDecimal? = null,
    @Column(name = "benchmark_index_code", length = 16)
    val benchmarkIndexCode: String? = null,
    @Column(name = "benchmark_change_pct", precision = 10, scale = 4)
    val benchmarkChangePct: BigDecimal? = null,
    @Column(name = "alpha_pct", precision = 10, scale = 4)
    val alphaPct: BigDecimal? = null,
    @Column(name = "market_regime", length = 16)
    val marketRegime: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    /** Hibernate 빈 생성자 (proxy 생성용) */
    protected constructor() : this(
        userId = "",
        strategyId = 0L,
        tradeDate = LocalDate.EPOCH,
        roundTripCount = 0,
        winCount = 0,
        lossCount = 0,
        realizedPnl = BigDecimal.ZERO,
        totalCostBasis = BigDecimal.ZERO,
    )
}

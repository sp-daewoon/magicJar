package kr.spdaewoon.magicjar.api.persistence.performance

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
 * strategy_daily_performance JPA 엔티티 — Flyway V85 정합 (ADR 0126).
 *
 * 전략×일자 성과 누적 원장. 배치(DailyPerformanceJob/백필 — 웨이브 B)가 멱등 UPSERT 로 채우고,
 * 본 PR(웨이브 A)은 **읽기 전용 조회 API** 가 이 엔티티만 의존한다(배치 미가동 = 0건 정상).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 * UQ (user_id, strategy_id, trade_date) — 배치/백필 멱등성. INDEX (strategy_id, trade_date DESC).
 *
 * benchmark·alpha·regime 은 지수 데이터와 합쳐 배치가 채우므로 조회 시 null 일 수 있다(지수 부재 날).
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6 읽기 전용·새 KIS 호출 0)
 */
@Entity
@Table(name = "strategy_daily_performance")
class StrategyDailyPerformanceJpaEntity(
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
    val returnPct: BigDecimal?,
    @Column(name = "benchmark_index_code", length = 16)
    val benchmarkIndexCode: String?,
    @Column(name = "benchmark_change_pct", precision = 10, scale = 4)
    val benchmarkChangePct: BigDecimal?,
    @Column(name = "alpha_pct", precision = 10, scale = 4)
    val alphaPct: BigDecimal?,
    @Column(name = "market_regime", length = 16)
    val marketRegime: String?,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)

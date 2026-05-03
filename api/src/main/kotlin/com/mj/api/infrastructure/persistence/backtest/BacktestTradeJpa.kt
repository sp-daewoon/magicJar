package com.mj.api.infrastructure.persistence.backtest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 라운드트립 거래 영속 엔티티 (track9 R1, V15).
 *
 * 1 run = N trades. run_id로 묶이며 CASCADE 삭제.
 */
@Entity
@Table(
    name = "backtest_trade",
    indexes = [
        Index(name = "idx_backtest_trade_run_seq", columnList = "run_id, sequence"),
        Index(name = "idx_backtest_trade_run_entry", columnList = "run_id, entry_at"),
    ],
)
class BacktestTradeJpa(
    @Id
    @Column(name = "trade_id", length = 64, nullable = false)
    var tradeId: String,
    @Column(name = "run_id", length = 64, nullable = false)
    var runId: String,
    @Column(name = "symbol", length = 16, nullable = false)
    var symbol: String,
    @Column(name = "sequence", nullable = false)
    var sequence: Int,
    @Column(name = "entry_at", nullable = false)
    var entryAt: Instant,
    @Column(name = "exit_at", nullable = false)
    var exitAt: Instant,
    @Column(name = "entry_price", nullable = false, precision = 18, scale = 4)
    var entryPrice: BigDecimal,
    @Column(name = "exit_price", nullable = false, precision = 18, scale = 4)
    var exitPrice: BigDecimal,
    @Column(name = "shares", nullable = false)
    var shares: Int,
    @Column(name = "pnl", nullable = false, precision = 18, scale = 4)
    var pnl: BigDecimal,
    @Column(name = "pnl_percent", nullable = false, precision = 10, scale = 8)
    var pnlPercent: BigDecimal,
    @Column(name = "entry_commission", nullable = false, precision = 18, scale = 4)
    var entryCommission: BigDecimal,
    @Column(name = "exit_commission", nullable = false, precision = 18, scale = 4)
    var exitCommission: BigDecimal,
    @Column(name = "exit_tax", nullable = false, precision = 18, scale = 4)
    var exitTax: BigDecimal,
    @Column(name = "exit_reason", length = 32, nullable = false)
    var exitReason: String,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
)

package com.mj.batch.infrastructure.persistence.backtest

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * batch 전용 BacktestTrade 엔티티 (track9 R1, V15).
 *
 * 백테스트 실행 후 trades 영속용. api 모듈에도 동일 테이블 매핑이 있다 (MSA 정석:
 * 각 앱이 자기 어댑터를 보유. 인덱스·제약은 V15 SQL이 단일 원천).
 */
@Entity
@Table(name = "backtest_trade")
class BacktestTradeJpa(
    @Id @Column(name = "trade_id", length = 64) var tradeId: String,
    @Column(name = "run_id", length = 64) var runId: String,
    @Column(name = "symbol", length = 16) var symbol: String,
    @Column(name = "sequence") var sequence: Int,
    @Column(name = "entry_at") var entryAt: Instant,
    @Column(name = "exit_at") var exitAt: Instant,
    @Column(name = "entry_price", precision = 18, scale = 4) var entryPrice: BigDecimal,
    @Column(name = "exit_price", precision = 18, scale = 4) var exitPrice: BigDecimal,
    @Column(name = "shares") var shares: Int,
    @Column(name = "pnl", precision = 18, scale = 4) var pnl: BigDecimal,
    @Column(name = "pnl_percent", precision = 10, scale = 8) var pnlPercent: BigDecimal,
    @Column(name = "entry_commission", precision = 18, scale = 4) var entryCommission: BigDecimal,
    @Column(name = "exit_commission", precision = 18, scale = 4) var exitCommission: BigDecimal,
    @Column(name = "exit_tax", precision = 18, scale = 4) var exitTax: BigDecimal,
    @Column(name = "exit_reason", length = 32) var exitReason: String,
    @Column(name = "created_at") var createdAt: Instant,
)

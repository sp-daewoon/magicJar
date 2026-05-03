package com.mj.batch.infrastructure.persistence.backtest

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

/**
 * batch 전용 BacktestEquityPoint 엔티티 (track9 R1, V15).
 */
@Entity
@Table(name = "backtest_equity_point")
class BacktestEquityPointJpa(
    @EmbeddedId var id: BacktestEquityPointId,
    @Column(name = "at") var at: Instant,
    @Column(name = "equity", precision = 20, scale = 4) var equity: BigDecimal,
    @Column(name = "cash", precision = 20, scale = 4) var cash: BigDecimal,
    @Column(name = "position_value", precision = 20, scale = 4) var positionValue: BigDecimal,
)

@Embeddable
data class BacktestEquityPointId(
    @Column(name = "run_id", length = 64) var runId: String = "",
    @Column(name = "sequence") var sequence: Int = 0,
) : Serializable

package com.mj.api.infrastructure.persistence.backtest

import jakarta.persistence.Column
import jakarta.persistence.Embeddable
import jakarta.persistence.EmbeddedId
import jakarta.persistence.Entity
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.io.Serializable
import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 자산곡선 1점 영속 엔티티 (track9 R1, V15).
 *
 * (run_id, sequence) 복합 PK. 시계열 순서 보존.
 */
@Entity
@Table(
    name = "backtest_equity_point",
    indexes = [
        Index(name = "idx_backtest_equity_run_at", columnList = "run_id, at"),
    ],
)
class BacktestEquityPointJpa(
    @EmbeddedId
    var id: BacktestEquityPointId,
    @Column(name = "at", nullable = false)
    var at: Instant,
    @Column(name = "equity", nullable = false, precision = 20, scale = 4)
    var equity: BigDecimal,
    @Column(name = "cash", nullable = false, precision = 20, scale = 4)
    var cash: BigDecimal,
    @Column(name = "position_value", nullable = false, precision = 20, scale = 4)
    var positionValue: BigDecimal,
)

@Embeddable
data class BacktestEquityPointId(
    @Column(name = "run_id", length = 64, nullable = false)
    var runId: String = "",
    @Column(name = "sequence", nullable = false)
    var sequence: Int = 0,
) : Serializable

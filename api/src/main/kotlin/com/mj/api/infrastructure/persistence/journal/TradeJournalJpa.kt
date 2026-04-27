package com.mj.api.infrastructure.persistence.journal

import com.mj.domain.common.Mode
import com.mj.domain.common.Side
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * `trade_journal` 테이블 매핑. data class 금지 — JPA + kotlin-jpa(no-arg) 호환을 위해 일반 class 사용.
 *
 * 모든 JSONB 컬럼은 String으로 직렬화하여 저장. 도메인 ↔ JPA 매핑은 [TradeJournalRepositoryAdapter]에서 처리.
 */
@Entity
@Table(name = "trade_journal")
class TradeJournalJpa(
    @Id
    @Column(name = "id", nullable = false)
    var id: UUID,
    @Column(name = "trade_id", nullable = false)
    var tradeId: UUID,
    @Enumerated(EnumType.STRING)
    @Column(name = "mode", nullable = false, length = 16)
    var mode: Mode,
    @Column(name = "code", nullable = false, length = 16)
    var code: String,
    @Column(name = "name", nullable = false, length = 64)
    var name: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    var side: Side,
    @Column(name = "entry_at", nullable = false)
    var entryAt: Instant,
    @Column(name = "entry_price", nullable = false, precision = 20, scale = 4)
    var entryPrice: BigDecimal,
    @Column(name = "entry_qty", nullable = false)
    var entryQty: Int,
    @Column(name = "exit_at")
    var exitAt: Instant? = null,
    @Column(name = "exit_price", precision = 20, scale = 4)
    var exitPrice: BigDecimal? = null,
    @Column(name = "exit_qty")
    var exitQty: Int? = null,
    @Column(name = "pnl", precision = 20, scale = 4)
    var pnl: BigDecimal? = null,
    @Column(name = "pnl_rate", precision = 12, scale = 6)
    var pnlRate: BigDecimal? = null,
    @Column(name = "strategy_id", nullable = false, length = 64)
    var strategyId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_snapshot", columnDefinition = "jsonb", nullable = false)
    var entrySnapshot: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exit_snapshot", columnDefinition = "jsonb", nullable = false)
    var exitSnapshot: String = "{}",
    @Column(name = "entry_reason", nullable = false, columnDefinition = "text")
    var entryReason: String,
    @Column(name = "exit_reason", columnDefinition = "text")
    var exitReason: String? = null,
    @Column(name = "ai_explanation", columnDefinition = "text")
    var aiExplanation: String? = null,
    @Column(name = "ai_review", columnDefinition = "text")
    var aiReview: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb", nullable = false)
    var tags: String = "[]",
    @Column(name = "user_note", columnDefinition = "text")
    var userNote: String? = null,
    @Column(name = "created_at", nullable = false)
    var createdAt: Instant,
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant,
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null,
)

package com.mj.consumer.infrastructure.persistence.journal

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

/** consumer 전용 TradeJournal 엔티티 (JournalRecorderListener → order.executed → journal entry 쓰기). */
@Entity
@Table(name = "trade_journal")
class TradeJournalJpa(
    @Id @Column(name = "id") var id: UUID,
    @Column(name = "trade_id") var tradeId: UUID,
    @Enumerated(EnumType.STRING) @Column(name = "mode", length = 16) var mode: Mode,
    @Column(name = "code", length = 16) var code: String,
    @Column(name = "name", length = 64) var name: String,
    @Enumerated(EnumType.STRING) @Column(name = "side", length = 8) var side: Side,
    @Column(name = "entry_at") var entryAt: Instant,
    @Column(name = "entry_price", precision = 20, scale = 4) var entryPrice: BigDecimal,
    @Column(name = "entry_qty") var entryQty: Int,
    @Column(name = "exit_at") var exitAt: Instant? = null,
    @Column(name = "exit_price", precision = 20, scale = 4) var exitPrice: BigDecimal? = null,
    @Column(name = "exit_qty") var exitQty: Int? = null,
    @Column(name = "pnl", precision = 20, scale = 4) var pnl: BigDecimal? = null,
    @Column(name = "pnl_rate", precision = 12, scale = 6) var pnlRate: BigDecimal? = null,
    @Column(name = "strategy_id", length = 64) var strategyId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_snapshot", columnDefinition = "jsonb") var entrySnapshot: String = "{}",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "exit_snapshot", columnDefinition = "jsonb") var exitSnapshot: String = "{}",
    @Column(name = "entry_reason", columnDefinition = "text") var entryReason: String,
    @Column(name = "exit_reason", columnDefinition = "text") var exitReason: String? = null,
    @Column(name = "ai_explanation", columnDefinition = "text") var aiExplanation: String? = null,
    @Column(name = "ai_review", columnDefinition = "text") var aiReview: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", columnDefinition = "jsonb") var tags: String = "[]",
    @Column(name = "user_note", columnDefinition = "text") var userNote: String? = null,
    @Column(name = "created_at") var createdAt: Instant,
    @Column(name = "updated_at") var updatedAt: Instant,
    @Column(name = "deleted_at") var deletedAt: Instant? = null,
)

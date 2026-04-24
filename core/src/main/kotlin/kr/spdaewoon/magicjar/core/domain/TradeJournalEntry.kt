package kr.spdaewoon.magicjar.core.domain

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class TradeJournalEntry(
    val id: UUID,
    val tradeId: UUID,
    val mode: Mode,
    val code: String,
    val name: String,
    val side: Side,
    val entryAt: Instant,
    val entryPrice: BigDecimal,
    val entryQty: Int,
    val exitAt: Instant? = null,
    val exitPrice: BigDecimal? = null,
    val exitQty: Int? = null,
    val pnl: BigDecimal? = null,
    val pnlRate: BigDecimal? = null,
    val strategyId: String,
    val entrySnapshot: Map<String, BigDecimal>,
    val exitSnapshot: Map<String, BigDecimal> = emptyMap(),
    val entryReason: String,
    val exitReason: String? = null,
    val aiExplanation: String? = null,
    val aiReview: String? = null,
    val tags: List<String> = emptyList(),
    val userNote: String? = null,
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant? = null,
)

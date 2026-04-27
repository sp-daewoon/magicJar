package com.mj.domain.journal.event

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/** Kafka 토픽 journal.entry.v1 메시지 스키마 — 매매일지 영속 완료 알림 */
data class JournalEntryEvent(
    val entryId: UUID,
    val tradeId: UUID,
    val code: String,
    val mode: String,
    val pnl: BigDecimal?,
    val pnlRate: BigDecimal?,
    val strategyId: String,
    val createdAt: Instant,
)

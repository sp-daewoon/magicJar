package kr.spdaewoon.magicjar.domain.journal

import java.math.BigDecimal
import java.time.Instant

/**
 * 매매일지 도메인 엔티티 — ADR 0072 §9-2 Phase 1 산출물.
 *
 * order·signal·risk·reconcile·position 5 Kafka 이벤트 통합 시계열 영속.
 * Flyway V29 `trading_journal` 테이블 정합.
 *
 * - [sourceTable]: 원본 영속 테이블명 (order_execution · signal · risk_event · reconcile_run · position)
 * - [sourceId]: 원본 PK
 * - [payload]: 원본 이벤트 페이로드 JSON 문자열 (audit + W4 AI context)
 * - [summary]: 한국어 요약 ("체결: 삼성전자 매수 100주 @ 75,000원")
 * - [realizedPnl]: 체결 이벤트 한정 (그 외 null)
 *
 * 멱등성 UQ `ux_trading_journal_source` (source_table + source_id + event_type):
 * Kafka at-least-once 재처리 시 동일 source 중복 INSERT 회피.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
data class TradingJournalEntry(
    val id: Long? = null,
    val userId: Long,
    val eventType: JournalEventType,
    val instrumentCode: String? = null,
    val instrumentName: String? = null,
    val occurredAt: Instant,
    val summary: String,
    val payload: String, // JSON 문자열 (JSONB 영속)
    val sourceTable: String,
    val sourceId: Long,
    val realizedPnl: BigDecimal? = null,
    val createdAt: Instant = Instant.now(),
    val notes: List<JournalNote> = emptyList(),
)

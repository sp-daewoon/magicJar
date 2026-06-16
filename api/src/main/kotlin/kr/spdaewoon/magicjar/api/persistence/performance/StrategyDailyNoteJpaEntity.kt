package kr.spdaewoon.magicjar.api.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/**
 * strategy_daily_note JPA 엔티티 — Flyway V87 정합 (ADR 0126).
 *
 * 전략×일자 자유 메모 + 태그. journal_note(V29) 패턴 참고. (user,strategy,date)당 단일 메모 UPSERT(수정 가능).
 *
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 * UQ (user_id, strategy_id, trade_date) — 단일 메모 UPSERT 키. INDEX (strategy_id, trade_date DESC).
 *
 * tags 는 JSONB 컬럼([SqlTypes.JSON]) 으로 `List<String>` 직렬화 — AuditTrailJpaEntity·UserPreference 패턴 정합.
 *
 * // AI 호출 0 — T-PERF 트랙 정책 (spec §6 읽기 전용·메모 CRUD)
 */
@Entity
@Table(name = "strategy_daily_note")
class StrategyDailyNoteJpaEntity(
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
    @Column(name = "note_text", columnDefinition = "text")
    val noteText: String?,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tags", nullable = false, columnDefinition = "JSONB")
    val tags: List<String> = emptyList(),
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
)

package com.mj.api.infrastructure.persistence.ai

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.OffsetDateTime

/**
 * `ai_analysis_log` (V17) — AI 분석 보조 호출 audit (T16, ADR 0046).
 *
 * audit-only — 도메인 흐름·UI 캐시 용도 X.
 */
@Entity
@Table(name = "ai_analysis_log")
class AiAnalysisLogEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    /** SYMBOL_COMMENTARY · COMPANY_ANALYSIS · CHART_INTERPRETATION · INDICATOR_MEANING · JOURNAL_RETROSPECTIVE */
    @Column(name = "request_type", nullable = false, length = 40)
    val requestType: String,
    @Column(name = "target_ref", length = 64)
    val targetRef: String? = null,
    @Column(name = "input_summary", nullable = false, columnDefinition = "TEXT")
    val inputSummary: String,
    @Column(name = "output_text", nullable = false, columnDefinition = "TEXT")
    val outputText: String,
    @Column(name = "provider", nullable = false, length = 20)
    val provider: String,
    @Column(name = "model", nullable = false, length = 80)
    val model: String,
    @Column(name = "prompt_version", nullable = false, length = 20)
    val promptVersion: String,
    @Column(name = "tokens_input", nullable = false)
    val tokensInput: Int,
    @Column(name = "tokens_output", nullable = false)
    val tokensOutput: Int,
    @Column(name = "cost_usd", nullable = false, precision = 12, scale = 6)
    val costUsd: BigDecimal,
    @Column(name = "duration_ms", nullable = false)
    val durationMs: Long,
    @Column(name = "success", nullable = false)
    val success: Boolean,
    @Column(name = "error_message", columnDefinition = "TEXT")
    val errorMessage: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime = OffsetDateTime.now(),
)

interface AiAnalysisLogJpaRepository : JpaRepository<AiAnalysisLogEntity, Long> {
    fun findTop50ByUserIdOrderByCreatedAtDesc(userId: Long): List<AiAnalysisLogEntity>
}

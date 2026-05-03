package com.mj.batch.infrastructure.persistence.corp_action

import com.mj.domain.market.corp_action.CorpAction
import com.mj.domain.market.corp_action.CorpActionSource
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.time.LocalDate

/**
 * Corp Action JPA 엔티티 (V13 corp_action 테이블).
 *
 * payload JSONB에 type별 상세 정보를 저장한다.
 * 멱등 upsert: instrument_code + type + ex_date UNIQUE constraint 활용.
 */
@Entity
@Table(
    name = "corp_action",
    uniqueConstraints = [
        UniqueConstraint(
            name = "corp_action_code_type_exdate_uq",
            columnNames = ["instrument_code", "type", "ex_date"],
        ),
    ],
)
class CorpActionJpa(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    var id: Long = 0,
    @Column(name = "instrument_code", length = 12, nullable = false)
    var instrumentCode: String,
    /** SPLIT|BONUS_ISSUE|RIGHTS_ISSUE|DIVIDEND|MERGER|SPINOFF */
    @Column(name = "type", length = 24, nullable = false)
    var type: String,
    @Column(name = "ex_date", nullable = false)
    var exDate: LocalDate,
    /** type별 상세 JSON */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb")
    var payload: Map<String, Any?> = emptyMap(),
    /** KIS|DART|MANUAL */
    @Column(name = "source", length = 16, nullable = false)
    var source: String = "KIS",
    @Column(name = "applied_at")
    var appliedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    var updatedAt: Instant = Instant.now(),
)

// -------------------------------------------------------------------------
// 도메인 ↔ JPA 변환 확장함수
// -------------------------------------------------------------------------

fun CorpAction.toJpa(): CorpActionJpa =
    CorpActionJpa(
        id = id ?: 0,
        instrumentCode = instrumentCode,
        type = typeCode(),
        exDate = exDate,
        payload = toPayload(),
        source = source.name,
        appliedAt = appliedAt,
    )

private fun CorpAction.typeCode(): String =
    when (this) {
        is CorpAction.Split -> "SPLIT"
        is CorpAction.BonusIssue -> "BONUS_ISSUE"
        is CorpAction.RightsIssue -> "RIGHTS_ISSUE"
        is CorpAction.Dividend -> "DIVIDEND"
        is CorpAction.Merger -> "MERGER"
        is CorpAction.Spinoff -> "SPINOFF"
    }

private fun CorpAction.toPayload(): Map<String, Any?> =
    when (this) {
        is CorpAction.Split -> mapOf("ratio" to ratio.toPlainString())
        is CorpAction.BonusIssue -> mapOf("ratio" to ratio.toPlainString())
        is CorpAction.RightsIssue ->
            mapOf(
                "ratio" to ratio.toPlainString(),
                "subscription_price" to subscriptionPrice.toPlainString(),
            )
        is CorpAction.Dividend ->
            mapOf(
                "cash_amount" to cashAmount.toPlainString(),
                "stock_ratio" to stockRatio.toPlainString(),
            )
        is CorpAction.Merger ->
            mapOf(
                "merge_ratio" to mergeRatio.toPlainString(),
                "surviving_code" to survivingCode,
            )
        is CorpAction.Spinoff -> mapOf("split_ratio" to splitRatio.toPlainString())
    }

fun CorpActionJpa.toDomain(): CorpAction {
    val src = runCatching { CorpActionSource.valueOf(source) }.getOrDefault(CorpActionSource.KIS)
    return when (type) {
        "SPLIT" ->
            CorpAction.Split(
                id = id,
                instrumentCode = instrumentCode,
                exDate = exDate,
                source = src,
                appliedAt = appliedAt,
                ratio = (payload["ratio"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ONE,
            )
        "BONUS_ISSUE" ->
            CorpAction.BonusIssue(
                id = id,
                instrumentCode = instrumentCode,
                exDate = exDate,
                source = src,
                appliedAt = appliedAt,
                ratio = (payload["ratio"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            )
        "RIGHTS_ISSUE" ->
            CorpAction.RightsIssue(
                id = id,
                instrumentCode = instrumentCode,
                exDate = exDate,
                source = src,
                appliedAt = appliedAt,
                ratio = (payload["ratio"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                subscriptionPrice = (payload["subscription_price"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            )
        "DIVIDEND" ->
            CorpAction.Dividend(
                id = id,
                instrumentCode = instrumentCode,
                exDate = exDate,
                source = src,
                appliedAt = appliedAt,
                cashAmount = (payload["cash_amount"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
                stockRatio = (payload["stock_ratio"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            )
        "MERGER" ->
            CorpAction.Merger(
                id = id,
                instrumentCode = instrumentCode,
                exDate = exDate,
                source = src,
                appliedAt = appliedAt,
                mergeRatio = (payload["merge_ratio"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ONE,
                survivingCode = payload["surviving_code"] as? String,
            )
        "SPINOFF" ->
            CorpAction.Spinoff(
                id = id,
                instrumentCode = instrumentCode,
                exDate = exDate,
                source = src,
                appliedAt = appliedAt,
                splitRatio = (payload["split_ratio"] as? String)?.toBigDecimalOrNull() ?: java.math.BigDecimal.ZERO,
            )
        else -> throw IllegalArgumentException("Unknown CorpAction type: $type")
    }
}

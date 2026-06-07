package kr.spdaewoon.magicjar.consumer.persistence

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.math.BigDecimal
import java.time.Instant

/**
 * strategy JPA entity copy for the consumer JVM.
 *
 * consumer는 api 모듈에 의존하지 않으므로 실시간 시그널 hot path에서 strategy 테이블을 직접 읽는다.
 */
@Entity
@Table(name = "strategy")
class ConsumerStrategyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "name", nullable = false, length = 200)
    val name: String,
    @Column(name = "name_ko", length = 200)
    val nameKo: String?,
    @Column(name = "dsl_entry", nullable = false, columnDefinition = "TEXT")
    val dslEntry: String,
    @Column(name = "dsl_exit", columnDefinition = "TEXT")
    val dslExit: String?,
    @Column(name = "universe_market", length = 20, nullable = false)
    val universeMarket: String = "KOSPI",
    @Column(name = "tf", length = 10, nullable = false)
    val tf: String = "D1",
    @Column(name = "fixed_stop_loss_pct", precision = 9, scale = 4)
    val fixedStopLossPct: BigDecimal? = null,
    @Column(name = "take_profit_pct", precision = 9, scale = 4)
    val takeProfitPct: BigDecimal? = null,
    // V72 추가 컬럼 — ADR 0114 진입(BUY) 집행 정책 (raw JSON. BUY 가격 산정 입력 — EntryPolicySerde round-trip).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "entry_policy", columnDefinition = "jsonb")
    val entryPolicy: String? = null,
    @Column(name = "ast_depth", nullable = false)
    val astDepth: Int = 0,
    @Column(name = "node_count", nullable = false)
    val nodeCount: Int = 0,
    @Column(name = "indicator_call_count", nullable = false)
    val indicatorCallCount: Int = 0,
    @Column(name = "estimated_eval_time_ms", nullable = false)
    val estimatedEvalTimeMs: Int = 0,
    @Column(name = "complexity_class", length = 20, nullable = false)
    val complexityClass: String = "LOW",
    @Column(name = "disabled_reason", length = 32)
    val disabledReason: String? = null,
    @Column(name = "last_disabled_at")
    val lastDisabledAt: Instant? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "eval_metric_summary", columnDefinition = "jsonb")
    val evalMetricSummary: String? = null,
    @Column(name = "status", nullable = false, length = 16)
    val status: String = "ACTIVE",
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "codes", columnDefinition = "jsonb")
    val codes: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "intervals", columnDefinition = "jsonb")
    val intervals: String? = null,
    @Column(name = "paused_at")
    val pausedAt: Instant? = null,
    @Column(name = "disabled_at")
    val disabledAt: Instant? = null,
    @Column(name = "deleted_at")
    val deletedAt: Instant? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
    @Column(name = "updated_at", nullable = false)
    val updatedAt: Instant = Instant.now(),
) {
    protected constructor() : this(
        userId = 0L,
        name = "",
        nameKo = null,
        dslEntry = "",
        dslExit = null,
    )
}

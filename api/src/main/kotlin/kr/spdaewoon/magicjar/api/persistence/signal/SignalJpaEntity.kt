package kr.spdaewoon.magicjar.api.persistence.signal

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
 * 시그널 JPA 엔티티 — Flyway V15 정합 (ADR 0066 §5-2 + §2-5 E-1).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 *
 * UQ (strategy_id, code, interval, ts) — DB constraint V15 정합 → 멱등성 보장.
 *
 * // AI 호출 0 — T-SIG 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8)
 */
@Entity
@Table(name = "signal")
class SignalJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "user_id", nullable = false, length = 40)
    val userId: String,
    @Column(name = "code", nullable = false, length = 8)
    val code: String,
    @Column(name = "interval", nullable = false, length = 8)
    val interval: String,
    @Column(name = "ts", nullable = false)
    val ts: Instant,
    @Column(name = "side", nullable = false, length = 4)
    val side: String,
    @Column(name = "strength", nullable = false)
    val strength: Int = 0,
    @Column(name = "reason", length = 500)
    val reason: String? = null,
    @Column(name = "price", precision = 19, scale = 4)
    val price: BigDecimal? = null,
    @Column(name = "rule_signal", length = 80)
    val ruleSignal: String? = null,
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "context_json", columnDefinition = "jsonb")
    val contextJson: String? = null,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

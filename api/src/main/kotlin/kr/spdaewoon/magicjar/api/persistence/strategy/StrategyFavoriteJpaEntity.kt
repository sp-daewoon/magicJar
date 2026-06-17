package kr.spdaewoon.magicjar.api.persistence.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * 전략 즐겨찾기 JPA 엔티티 — Flyway V88 strategy_favorite (spec 2026-06-18 §5.2).
 *
 * ADR 0051 주의: no-arg constructor 필요 — kotlin-jpa 플러그인(api 모듈 적용) 의존.
 * 헌법 §6: body 가진 fun 0건.
 *
 * UNIQUE (user_id, strategy_id) — 멱등 add는 [StrategyFavoriteJpaAdapter]가 존재 체크로 보장.
 */
@Entity
@Table(name = "strategy_favorite")
class StrategyFavoriteJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "user_id", nullable = false)
    val userId: Long,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

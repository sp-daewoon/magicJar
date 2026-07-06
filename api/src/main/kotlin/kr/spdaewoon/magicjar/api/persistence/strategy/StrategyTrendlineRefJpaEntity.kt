package kr.spdaewoon.magicjar.api.persistence.strategy

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

/**
 * `strategy_trendline_ref` JPA 엔티티 — Flyway V99 정합 (ADR 0140 §3-3 · R2).
 *
 * 선-전략 참조 무결성. 전략 저장 시 DSL `trendline(id)` 노드에서 추출한 참조를 동기화한다
 * ([kr.spdaewoon.magicjar.api.service.strategy.StrategyTrendlineReferenceService]).
 * 선 삭제 가드·강제삭제 PAUSED 가 이 테이블을 역참조(trendline_id) 조회한다.
 *
 * kotlin-jpa 플러그인(api 적용)이 no-arg constructor 생성. 헌법 §6: body 가진 fun 0건.
 */
@Entity
@Table(name = "strategy_trendline_ref")
class StrategyTrendlineRefJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "strategy_id", nullable = false)
    val strategyId: Long,
    @Column(name = "trendline_id", nullable = false)
    val trendlineId: Long,
    @Column(name = "created_at", nullable = false, updatable = false)
    val createdAt: Instant = Instant.now(),
)

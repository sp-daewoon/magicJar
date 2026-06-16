package kr.spdaewoon.magicjar.batch.persistence.performance

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table

/**
 * batch 모듈용 strategy 경량 JPA 엔티티 (읽기 전용 · 최소 필드) — Flyway V9 `strategy` 정합.
 *
 * 전략 왕복 집계 시 benchmark_index_code 산정을 위해 universe_market 만 조회한다(spec §4-2).
 * order_request 에는 strategy_id 만 있고 universe_market 이 없으므로 strategy 테이블 조회 필요.
 * 헌법 §6 (ADR 0051): body 가진 fun 0건.
 *
 * // AI 호출 0 / KIS 호출 0 — 영속 데이터 reuse (ADR 0126 §3-6)
 */
@Entity
@Table(name = "strategy")
class BatchStrategyJpaEntity(
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    val id: Long? = null,
    @Column(name = "universe_market", nullable = false, length = 20)
    val universeMarket: String = "KOSPI",
) {
    /** Hibernate 빈 생성자 (proxy 생성용) */
    protected constructor() : this(id = null, universeMarket = "KOSPI")
}

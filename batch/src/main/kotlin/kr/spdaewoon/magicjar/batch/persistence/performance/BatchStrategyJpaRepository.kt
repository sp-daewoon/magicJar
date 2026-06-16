package kr.spdaewoon.magicjar.batch.persistence.performance

import org.springframework.data.jpa.repository.JpaRepository

/**
 * batch 모듈 strategy 읽기 전용 Repository — 헌법 §6 abstract fun only LOCK.
 *
 * 전략 왕복 집계 시 universe_market → benchmark 지수 매핑용(spec §4-2).
 *
 * // AI 호출 0 / KIS 호출 0 (ADR 0126 §3-6)
 */
interface BatchStrategyJpaRepository : JpaRepository<BatchStrategyJpaEntity, Long>

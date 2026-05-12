package kr.spdaewoon.magicjar.api.persistence.signal

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.time.Instant

/**
 * 시그널 Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [SignalJpaAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-SIG 트랙 정책
 */
interface SignalJpaRepository : JpaRepository<SignalJpaEntity, Long> {
    fun findByStrategyIdAndCodeAndIntervalAndTs(
        strategyId: Long,
        code: String,
        interval: String,
        ts: Instant,
    ): SignalJpaEntity?

    fun findByUserIdAndTsBetween(
        userId: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<SignalJpaEntity>
}

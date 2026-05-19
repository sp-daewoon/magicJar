package kr.spdaewoon.magicjar.api.persistence.order

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * order_event_outbox Spring Data JPA 리포지터리 (ADR 0084 P1-1 + Flyway V40).
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * helper / branching 은 [OrderEventOutboxRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0084 §1-2)
 */
interface OrderEventOutboxJpaRepository : JpaRepository<OrderEventOutboxJpaEntity, Long> {
    /** PENDING worker poll — published_at IS NULL · 오래된 순 */
    fun findByPublishedAtIsNullOrderByCreatedAtAsc(pageable: Pageable): List<OrderEventOutboxJpaEntity>

    /** 상태별 운영 조회 — created_at ASC */
    fun findByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: Pageable,
    ): List<OrderEventOutboxJpaEntity>
}

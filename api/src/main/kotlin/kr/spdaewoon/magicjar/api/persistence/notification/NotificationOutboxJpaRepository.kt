package kr.spdaewoon.magicjar.api.persistence.notification

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * notification_outbox Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [NotificationOutboxRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
interface NotificationOutboxJpaRepository : JpaRepository<NotificationOutboxJpaEntity, Long> {
    /** PENDING 상태 아웃박스 목록 — created_at ASC (오래된 것 먼저 재시도) */
    fun findByStatusOrderByCreatedAtAsc(
        status: String,
        pageable: Pageable,
    ): List<NotificationOutboxJpaEntity>
}

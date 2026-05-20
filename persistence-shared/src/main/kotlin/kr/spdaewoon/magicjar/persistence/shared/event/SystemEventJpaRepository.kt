package kr.spdaewoon.magicjar.persistence.shared.event

import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository

/**
 * system_event Spring Data JPA 리포지터리 — Flyway V44 정합.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 * helper 는 caller adapter (예: P2 SystemEventQueryService) private fun 위치.
 *
 * ### 쿼리
 * - [findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc] : 사용자별 미확인 (UI 알림센터 — index idx_system_event_user_unread)
 * - [findAllByOrderByOccurredAtDesc]                     : 운영 dashboard 페이지네이션 전체 (P2 admin)
 * - [findByCorrelationIdOrderByOccurredAtAsc]            : correlation_id 그룹 표시 (WS disconnect→reconnect 묶음 — P2)
 *
 * // AI 호출 0 — ADR 0023 §2-4 정합
 */
interface SystemEventJpaRepository : JpaRepository<SystemEventJpaEntity, Long> {
    fun findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(
        userId: String,
        pageable: Pageable,
    ): List<SystemEventJpaEntity>

    fun findAllByOrderByOccurredAtDesc(pageable: Pageable): List<SystemEventJpaEntity>

    fun findByCorrelationIdOrderByOccurredAtAsc(correlationId: String): List<SystemEventJpaEntity>
}

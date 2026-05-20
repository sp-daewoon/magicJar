package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.controller.SystemEventPageDto
import kr.spdaewoon.magicjar.api.controller.SystemEventResponseDto
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaEntity
import kr.spdaewoon.magicjar.persistence.shared.event.SystemEventJpaRepository
import org.springframework.data.domain.PageRequest
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

/**
 * P2 — 시스템 이벤트 조회·마킹 service.
 *
 * 3 op:
 * - [findList]   : 사용자별/전체 페이지네이션 + unread count 계산
 * - [markViewed] : viewed_at = now 마킹 (사용자 확인 → 벨 badge 감소)
 * - [markResolved] : resolved_at = now 마킹 (운영자 dismiss → dashboard 시각화)
 *
 * ### 헌법 §6 + ADR 0051 정합
 * Repository interface 안에 body 가진 fun 0건 — `toResponse()` mapper는 본 service 의
 * private extension fun 으로 격리 (default fun 함정 회피).
 *
 * ### 트랜잭션
 * - findList: `@Transactional(readOnly = true)`
 * - markViewed/markResolved: 기본 `@Transactional` (write — Hibernate dirty checking)
 *
 * spec: `_workspace/specs/2026-05-20-system-event-log-design.md` §6 + §13
 * plan: `_workspace/plans/2026-05-20-system-event-log-p2-implementation-plan.md` Task 2
 *
 * AI 호출 0 — ADR 0023 §2-4 정합.
 */
@Service
class SystemEventQueryService(
    private val jpa: SystemEventJpaRepository,
) {
    @Transactional(readOnly = true)
    fun findList(
        userId: String?,
        unreadOnly: Boolean,
        page: Int,
        size: Int,
    ): SystemEventPageDto {
        val pageable = PageRequest.of(page, size)
        val items =
            if (userId != null && unreadOnly) {
                jpa.findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(userId, pageable)
            } else {
                jpa.findAllByOrderByOccurredAtDesc(pageable)
            }
        val totalCount = jpa.count()
        val unreadCount =
            if (userId != null) {
                jpa
                    .findByUserIdAndViewedAtIsNullOrderByOccurredAtDesc(
                        userId,
                        PageRequest.of(0, Int.MAX_VALUE),
                    ).size
                    .toLong()
            } else {
                0L
            }
        return SystemEventPageDto(
            items = items.map { it.toResponse() },
            totalCount = totalCount,
            unreadCount = unreadCount,
            page = page,
            size = size,
        )
    }

    @Transactional
    fun markViewed(id: Long): SystemEventResponseDto? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.viewedAt = Instant.now()
        return jpa.save(entity).toResponse()
    }

    @Transactional
    fun markResolved(id: Long): SystemEventResponseDto? {
        val entity = jpa.findById(id).orElse(null) ?: return null
        entity.resolvedAt = Instant.now()
        return jpa.save(entity).toResponse()
    }

    private fun SystemEventJpaEntity.toResponse(): SystemEventResponseDto =
        SystemEventResponseDto(
            id = id ?: 0L,
            category = category,
            severity = severity,
            actor = actor,
            action = action,
            userId = userId,
            correlationId = correlationId,
            detailJson = detailJson,
            occurredAt = occurredAt,
            viewedAt = viewedAt,
            resolvedAt = resolvedAt,
        )
}

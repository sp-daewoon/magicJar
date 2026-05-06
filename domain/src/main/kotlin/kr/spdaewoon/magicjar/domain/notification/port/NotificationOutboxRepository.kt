package kr.spdaewoon.magicjar.domain.notification.port

import kr.spdaewoon.magicjar.domain.notification.NotificationOutbox
import kr.spdaewoon.magicjar.domain.notification.OutboxStatus

/**
 * 알림 아웃박스 영속 포트 (ADR 0044).
 */
interface NotificationOutboxRepository {
    fun findPending(limit: Int = 100): List<NotificationOutbox>

    fun findByStatus(status: OutboxStatus): List<NotificationOutbox>

    fun save(outbox: NotificationOutbox): NotificationOutbox

    fun markSent(id: Long): NotificationOutbox?

    fun markFailed(
        id: Long,
        error: String,
    ): NotificationOutbox?
}

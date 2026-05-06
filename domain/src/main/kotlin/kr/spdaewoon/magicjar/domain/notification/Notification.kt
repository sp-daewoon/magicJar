package kr.spdaewoon.magicjar.domain.notification

import kotlinx.datetime.Instant

/**
 * 알림 이벤트 도메인 (ADR 0044).
 */
data class NotificationEvent(
    val topic: String,
    val payload: Map<String, Any>,
)

/**
 * 알림 발송 실패 아웃박스 (ADR 0044 — outbox 패턴).
 */
data class NotificationOutbox(
    val id: Long? = null,
    val eventTopic: String,
    val payloadJson: String,
    val status: OutboxStatus = OutboxStatus.PENDING,
    val retryCount: Int = 0,
    val lastError: String? = null,
    val createdAt: Instant,
    val sentAt: Instant? = null,
)

enum class OutboxStatus { PENDING, SENT, FAILED }

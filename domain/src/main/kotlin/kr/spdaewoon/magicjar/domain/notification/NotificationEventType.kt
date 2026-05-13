package kr.spdaewoon.magicjar.domain.notification

/**
 * 알림 이벤트 유형 enum (ADR 0044 + ADR 0070 §2-4 LOCK).
 *
 * Kafka 7 listener 매트릭스 정합:
 * - ORDER_FILLED       → order.filled.v1       (P1)
 * - ORDER_REJECTED     → order.rejected.v1     (P1)
 * - ORDER_CANCELLED    → order.cancelled.v1    (P2)
 * - RISK_VIOLATION     → risk.violation.v1     (P0)
 * - RECONCILE_DRIFT    → reconcile.drift.v1    (P0)
 * - KILLSWITCH_ENGAGED → killswitch.engaged.v1 (P0)
 * - RECONCILE_RUN      → reconcile.run.v1      (P2 — audit only)
 *
 * // AI 호출 0 — T-NOTIFY 트랙 정책 (ADR 0023 §2-4 + ADR 0070 §1-2)
 */
enum class NotificationEventType(
    val defaultPriority: NotificationPriority,
) {
    ORDER_FILLED(NotificationPriority.P1),
    ORDER_REJECTED(NotificationPriority.P1),
    ORDER_CANCELLED(NotificationPriority.P2),
    RISK_VIOLATION(NotificationPriority.P0),
    RECONCILE_DRIFT(NotificationPriority.P0),
    KILLSWITCH_ENGAGED(NotificationPriority.P0),
    RECONCILE_RUN(NotificationPriority.P2),
}

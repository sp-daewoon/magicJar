package kr.spdaewoon.magicjar.domain.journal

/**
 * 매매일지 이벤트 타입 enum — ADR 0072 §3-1 (G-1 5 토픽 listen 매트릭스 정합).
 *
 * Kafka 5 토픽 → trading_journal row 변환 시 event_type 컬럼 값.
 *
 * // AI 호출 0 — T-J 트랙 정책 (ADR 0072 §2-8 H-1 LOCK)
 */
enum class JournalEventType {
    /** order.filled.v1 → 체결 이벤트 */
    ORDER_FILLED,

    /** order.rejected.v1 → 주문 거부 이벤트 */
    ORDER_REJECTED,

    /** order.cancelled.v1 → 주문 취소 이벤트 */
    ORDER_CANCELLED,

    /** signal.generated.v1 → 시그널 발생 이벤트 */
    SIGNAL_GENERATED,

    /** risk.violation.v1 → 리스크 위반 이벤트 */
    RISK_VIOLATION,
}

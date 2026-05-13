package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 감사 이벤트 유형 (ADR 0068 §14-1 enum LOCK).
 *
 * §14-2 OrderRouterKafkaListener 12 step + §14-4 risk 매트릭스 + §14-6 흐름 분기 LOCK 정합.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class OrderAuditEventType {
    REAL_GATE_DENIED,
    IDEMPOTENCY_SKIPPED,
    KILLSWITCH_DENIED,
    KIS_ACK,
    KIS_REJECT,
    KIS_EXCEPTION,
    KIS_INVALID_RESPONSE,
    FILL_RECEIVED,
    PARTIAL_FILL,
    FULL_FILL,
    KIS_CANCEL_ACK,
    KIS_CANCEL_REJECT,
    EXECUTION_TIMEOUT,
    EXECUTION_OVERFLOW,
    REDIS_DOWN_FALLBACK,
}

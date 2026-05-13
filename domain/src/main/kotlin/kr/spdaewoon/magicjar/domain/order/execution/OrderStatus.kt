package kr.spdaewoon.magicjar.domain.order.execution

/**
 * 주문 상태 전이 (ADR 0068 §14-1 enum LOCK + §5-1 정합).
 *
 * 전이도:
 *   REQUESTED → ACK → PARTIALLY_FILLED → FILLED
 *   REQUESTED → REJECTED (3중 게이트 / 멱등 / KIS REST 실패)
 *   ACK       → CANCELLED (사용자 [취소] → KIS order-rvsecncl 성공)
 *   ACK       → EXPIRED   (1차 미도입 — 후속 자동 취소 worker용)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
enum class OrderStatus {
    REQUESTED,
    ACK,
    FILLED,
    PARTIALLY_FILLED,
    REJECTED,
    CANCELLED,
    EXPIRED,
}

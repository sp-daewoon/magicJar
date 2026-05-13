package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution

/**
 * OrderExecution(분할 체결) 영속 포트 (ADR 0068 §14-1 LOCK · 헌법 §6 abstract만).
 *
 * [sumExecutedQuantityByOrder] 누적 합으로 PARTIALLY_FILLED → FILLED 전이 판정 (§14-6).
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderExecutionRepository {
    fun save(execution: OrderExecution): OrderExecution

    fun findByOrderId(orderId: Long): List<OrderExecution>

    fun sumExecutedQuantityByOrder(orderId: Long): Int
}

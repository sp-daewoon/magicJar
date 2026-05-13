package kr.spdaewoon.magicjar.domain.order.execution.port

import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import java.time.Instant

/**
 * OrderRequest 영속 포트 (ADR 0068 §14-1 LOCK · 헌법 §6 default fun 함정 회피 — abstract만).
 *
 * Phase 2 backend-core JPA 어댑터가 구현. 본 PR은 인터페이스만.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderRequestRepository {
    fun save(request: OrderRequest): OrderRequest

    fun findById(id: Long): OrderRequest?

    fun findByKisOrderNo(kisOrderNo: String): OrderRequest?

    fun findByIdempotencyKey(idempotencyKey: String): OrderRequest?

    fun findOpenByUserId(userId: String): List<OrderRequest>

    fun updateStatus(
        id: Long,
        status: OrderStatus,
        rejectReason: String?,
    ): OrderRequest?

    fun updateKisAck(
        id: Long,
        kisOrderNo: String,
        kisFwdgOrgno: String,
        acknowledgedAt: Instant,
    ): OrderRequest?

    fun markCompleted(
        id: Long,
        completedAt: Instant,
        finalStatus: OrderStatus,
    ): OrderRequest?
}

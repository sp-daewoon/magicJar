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

    /**
     * ADR 0084 P0-2 — PENDING_RECONCILE 상태 + requestedAt < cutoff 조회.
     *
     * 사용처: [kr.spdaewoon.magicjar.consumer.order.OrderReconciliationWorker] cron 1분 — 5분 이상 경과한
     *        PENDING_RECONCILE 주문을 KIS inquire-daily-ccld로 검증.
     *
     * - status = PENDING_RECONCILE
     * - requestedAt < cutoff (즉, 너무 최근 row는 KIS 측 인덱스 미반영 위험 → 최소 grace period 부여)
     *
     * 정렬: requestedAt ASC (오래된 것 우선)
     */
    fun findByStatusAndRequestedAtBefore(
        status: OrderStatus,
        cutoff: Instant,
        limit: Int = 50,
    ): List<OrderRequest>

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

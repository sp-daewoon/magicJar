package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * order_request Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [OrderRequestRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderRequestJpaRepository :
    JpaRepository<OrderRequestJpaEntity, Long>,
    JpaSpecificationExecutor<OrderRequestJpaEntity> {
    fun findByKisOrderNo(kisOrderNo: String): OrderRequestJpaEntity?

    fun findAllByKisOrderNoOrderByIdAsc(kisOrderNo: String): List<OrderRequestJpaEntity>

    fun findByIdempotencyKey(idempotencyKey: String): OrderRequestJpaEntity?

    fun findByUserIdOrderByRequestedAtDesc(
        userId: String,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    @Query(
        "SELECT e FROM OrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED', 'PENDING_RECONCILE')",
    )
    fun findOpenByUserId(
        @Param("userId") userId: String,
    ): List<OrderRequestJpaEntity>

    fun findByUserIdAndStatusInAndRequestedAtBetween(
        userId: String,
        statuses: List<OrderStatus>,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    fun findByUserIdAndRequestedAtBetween(
        userId: String,
        from: Instant,
        to: Instant,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    /**
     * ADR 0084 P0-2 — PENDING_RECONCILE 검증 worker lookup (consumer/api 모듈 공통).
     *
     * V36 partial index `idx_order_request_pending_reconcile` 활용.
     */
    @Query(
        "SELECT e FROM OrderRequestJpaEntity e " +
            "WHERE e.status = :status AND e.requestedAt < :cutoff " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findByStatusAndRequestedAtBefore(
        @Param("status") status: OrderStatus,
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<OrderRequestJpaEntity>

    /**
     * KillSwitch race-safe guard B (Grace Window) — userId의 마지막 FILLED 시각.
     *
     * `completedAt MAX WHERE status=FILLED`. 한 건도 없으면 null.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT MAX(e.completedAt) FROM OrderRequestJpaEntity e " +
            "WHERE e.userId = :userId AND e.status = kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.FILLED",
    )
    fun findLastFilledAtByUserId(
        @Param("userId") userId: String,
    ): Instant?

    @Modifying
    @Query(
        value = "UPDATE order_execution SET order_id = :targetId WHERE order_id = :sourceId",
        nativeQuery = true,
    )
    fun reassignExecutions(
        @Param("sourceId") sourceId: Long,
        @Param("targetId") targetId: Long,
    ): Int

    @Modifying
    @Query(
        value = "UPDATE order_audit_event SET order_id = :targetId WHERE order_id = :sourceId",
        nativeQuery = true,
    )
    fun reassignAuditEvents(
        @Param("sourceId") sourceId: Long,
        @Param("targetId") targetId: Long,
    ): Int
}

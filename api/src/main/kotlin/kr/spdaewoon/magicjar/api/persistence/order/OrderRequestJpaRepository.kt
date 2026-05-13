package kr.spdaewoon.magicjar.api.persistence.order

import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
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
interface OrderRequestJpaRepository : JpaRepository<OrderRequestJpaEntity, Long> {
    fun findByKisOrderNo(kisOrderNo: String): OrderRequestJpaEntity?

    fun findByIdempotencyKey(idempotencyKey: String): OrderRequestJpaEntity?

    fun findByUserIdOrderByRequestedAtDesc(
        userId: String,
        pageable: Pageable,
    ): Page<OrderRequestJpaEntity>

    @Query(
        "SELECT e FROM OrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED')",
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
}

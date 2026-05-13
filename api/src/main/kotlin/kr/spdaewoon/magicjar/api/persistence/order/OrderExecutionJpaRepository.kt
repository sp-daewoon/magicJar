package kr.spdaewoon.magicjar.api.persistence.order

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

/**
 * order_execution Spring Data JPA 리포지터리.
 *
 * 헌법 §6 (ADR 0051) — Repository interface 내부 body 가진 fun 0건.
 * body 가진 helper는 [OrderExecutionRepositoryAdapter] private fun 위치.
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface OrderExecutionJpaRepository : JpaRepository<OrderExecutionJpaEntity, Long> {
    fun findByOrderIdOrderByExecutedAtAsc(orderId: Long): List<OrderExecutionJpaEntity>

    @Query(
        "SELECT COALESCE(SUM(e.executedQty), 0) FROM OrderExecutionJpaEntity e WHERE e.orderId = :orderId",
    )
    fun sumExecutedQtyByOrderId(
        @Param("orderId") orderId: Long,
    ): java.math.BigDecimal
}

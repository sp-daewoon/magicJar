package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.Instant

/**
 * order_request Spring Data JPA Repository — consumer 모듈.
 *
 * 헌법 §6 (ADR 0051): Repository interface 내부 body 가진 fun 0건.
 * 헬퍼는 [ConsumerOrderRequestRepositoryAdapter] private fun 위치.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
interface ConsumerOrderRequestJpaRepository : JpaRepository<ConsumerOrderRequestJpaEntity, Long> {
    fun findByKisOrderNo(kisOrderNo: String): ConsumerOrderRequestJpaEntity?

    fun findByIdempotencyKey(idempotencyKey: String): ConsumerOrderRequestJpaEntity?

    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED')",
    )
    fun findOpenByUserId(
        @Param("userId") userId: String,
    ): List<ConsumerOrderRequestJpaEntity>

    /**
     * ADR 0084 P0-2 — OrderReconciliationWorker cron 1분 lookup.
     *
     * V36 partial index `idx_order_request_pending_reconcile` (PENDING_RECONCILE 상태 한정) 활용.
     * Pageable로 limit 적용 — 한 cron tick에서 처리할 row 수 제한.
     */
    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.status = :status AND e.requestedAt < :cutoff " +
            "ORDER BY e.requestedAt ASC",
    )
    fun findByStatusAndRequestedAtBefore(
        @Param("status") status: OrderStatus,
        @Param("cutoff") cutoff: Instant,
        pageable: Pageable,
    ): List<ConsumerOrderRequestJpaEntity>

    /**
     * KillSwitch race-safe guard B (Grace Window) — userId의 마지막 FILLED 시각.
     *
     * `completedAt MAX WHERE status=FILLED`. 한 건도 없으면 null.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT MAX(e.completedAt) FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.userId = :userId AND e.status = kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.FILLED",
    )
    fun findLastFilledAtByUserId(
        @Param("userId") userId: String,
    ): Instant?
}

package kr.spdaewoon.magicjar.consumer.persistence

import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
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

    fun findAllByKisOrderNoOrderByIdAsc(kisOrderNo: String): List<ConsumerOrderRequestJpaEntity>

    fun findByIdempotencyKey(idempotencyKey: String): ConsumerOrderRequestJpaEntity?

    @Query(
        "SELECT e FROM ConsumerOrderRequestJpaEntity e WHERE e.userId = :userId " +
            "AND e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED', 'PENDING_RECONCILE')",
    )
    fun findOpenByUserId(
        @Param("userId") userId: String,
    ): List<ConsumerOrderRequestJpaEntity>

    @Query(
        "SELECT DISTINCT e.code FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.status IN ('REQUESTED', 'ACK', 'PARTIALLY_FILLED', 'PENDING_RECONCILE')",
    )
    fun findOpenCodes(): List<String>

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

    /**
     * ADR 0109 GAP-3 — canary 일일 REAL 주문 횟수 전역 cap 카운트.
     *
     * `mode = :mode` AND `source <> :excludedSource` AND `requestedAt ∈ [from, to)` 건수.
     * caller(adapter)가 REAL + EXTERNAL 제외 + KST 당일 UTC 경계 전달.
     *
     * ### EXTERNAL 제외 (2026-06-05 자본 path fix)
     * 외부(HTS·MTS) 직접 주문을 흡수한 EXTERNAL row 는 우리 자동매매 예산이 아니므로 canary cap 카운트에서 제외.
     * 사용자가 HTS 로 직접 매수해도 우리 자동매매 일일 cap 이 잠식되면 안 됨.
     * 헌법 §6 — interface body 0건, @Query 만 사용.
     */
    @Query(
        "SELECT COUNT(e) FROM ConsumerOrderRequestJpaEntity e " +
            "WHERE e.mode = :mode AND e.source <> :excludedSource " +
            "AND e.requestedAt >= :fromInclusive AND e.requestedAt < :toExclusive",
    )
    fun countRealOrdersBetween(
        @Param("mode") mode: TradingMode,
        @Param("excludedSource") excludedSource: OrderSource,
        @Param("fromInclusive") fromInclusive: Instant,
        @Param("toExclusive") toExclusive: Instant,
    ): Long

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

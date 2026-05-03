package com.mj.consumer.infrastructure.persistence.order

import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.order.OrderRecord
import com.mj.domain.order.port.OrderRepository
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * orders 테이블 JPA 엔티티. V9·V11 Flyway 대응.
 *
 * V11 변경:
 * - kisOrderNo / krxFwdgOrdOrgno NULLABLE (REJECTED 주문은 KIS 미도달)
 * - rejectReasonCode / rejectDetail / rejectSource 컬럼 추가 (ADR 0034)
 */
@Entity
@Table(name = "orders")
class OrderEntity(
    @Id
    @Column(name = "order_id")
    val orderId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: UUID,
    @Column(name = "account_no", nullable = false, length = 20)
    val accountNo: String,
    @Column(name = "kis_mode", nullable = false, length = 8)
    val kisMode: String,
    @Column(name = "intent_id", nullable = false)
    val intentId: UUID,
    /** V11: NULLABLE — REJECTED 주문은 KIS 미도달이므로 null. */
    @Column(name = "kis_order_no", length = 20)
    val kisOrderNo: String?,
    /** V11: NULLABLE — REJECTED 주문은 null. */
    @Column(name = "krx_fwdg_ord_orgno", length = 8)
    val krxFwdgOrdOrgno: String?,
    @Column(name = "code", nullable = false, length = 7)
    val code: String,
    @Enumerated(EnumType.STRING)
    @Column(name = "side", nullable = false, length = 8)
    val side: Side,
    @Column(name = "qty", nullable = false)
    val qty: Int,
    @Column(name = "price")
    val price: BigDecimal? = null,
    @Enumerated(EnumType.STRING)
    @Column(name = "order_type", nullable = false, length = 8)
    val orderType: OrderType,
    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    val status: OrderStatus,
    @Column(name = "filled_qty", nullable = false)
    val filledQty: Int = 0,
    @Column(name = "avg_fill_price")
    val avgFillPrice: BigDecimal? = null,
    @Column(name = "reject_reason", columnDefinition = "TEXT")
    val rejectReason: String? = null,
    /** V11: RejectReason.code (KILL_SWITCH·SESSION_CLOSED 등). */
    @Column(name = "reject_reason_code", length = 64)
    val rejectReasonCode: String? = null,
    /** V11: RejectReason.toDetail() 상세 메시지. */
    @Column(name = "reject_detail", columnDefinition = "TEXT")
    val rejectDetail: String? = null,
    /** V11: 거부 출처 — GUARD | KIS_API | INTERNAL. */
    @Column(name = "reject_source", length = 32)
    val rejectSource: String? = null,
    @Column(name = "created_at", nullable = false)
    val createdAt: OffsetDateTime,
    @Column(name = "updated_at", nullable = false)
    val updatedAt: OffsetDateTime,
) {
    fun toDomain(): OrderRecord =
        OrderRecord(
            orderId = orderId,
            userId = userId,
            accountNo = accountNo,
            kisMode = kisMode,
            intentId = intentId,
            kisOrderNo = kisOrderNo,
            krxFwdgOrdOrgno = krxFwdgOrdOrgno,
            code = code,
            side = side,
            qty = qty,
            price = price,
            orderType = orderType,
            status = status,
            filledQty = filledQty,
            avgFillPrice = avgFillPrice,
            rejectReason = rejectReason,
            rejectReasonCode = rejectReasonCode,
            rejectDetail = rejectDetail,
            rejectSource = rejectSource,
            createdAt = createdAt,
            updatedAt = updatedAt,
        )

    companion object {
        fun from(r: OrderRecord): OrderEntity =
            OrderEntity(
                orderId = r.orderId,
                userId = r.userId,
                accountNo = r.accountNo,
                kisMode = r.kisMode,
                intentId = r.intentId,
                kisOrderNo = r.kisOrderNo,
                krxFwdgOrdOrgno = r.krxFwdgOrdOrgno,
                code = r.code,
                side = r.side,
                qty = r.qty,
                price = r.price,
                orderType = r.orderType,
                status = r.status,
                filledQty = r.filledQty,
                avgFillPrice = r.avgFillPrice,
                rejectReason = r.rejectReason,
                rejectReasonCode = r.rejectReasonCode,
                rejectDetail = r.rejectDetail,
                rejectSource = r.rejectSource,
                createdAt = r.createdAt,
                updatedAt = r.updatedAt,
            )
    }
}

interface OrderJpaRepository : JpaRepository<OrderEntity, UUID> {
    fun findByKisModeAndKisOrderNo(
        kisMode: String,
        kisOrderNo: String,
    ): OrderEntity?

    @Query(
        """SELECT o FROM OrderEntity o WHERE o.userId = :userId
             AND o.status IN ('PENDING','PARTIALLY_FILLED')""",
    )
    fun findOpenByUser(
        @Param("userId") userId: UUID,
    ): List<OrderEntity>

    @Modifying
    @Query(
        """UPDATE OrderEntity o
              SET o.status = :status,
                  o.filledQty = :filledQty,
                  o.avgFillPrice = :avgFillPrice,
                  o.updatedAt = CURRENT_TIMESTAMP
              WHERE o.orderId = :orderId""",
    )
    fun updateStatus(
        @Param("orderId") orderId: UUID,
        @Param("status") status: OrderStatus,
        @Param("filledQty") filledQty: Int,
        @Param("avgFillPrice") avgFillPrice: BigDecimal?,
    ): Int

    /**
     * 사용자의 특정 종목 현재 포지션 평가액 계산.
     *
     * FILLED·PARTIALLY_FILLED 주문 기준:
     * - BUY: filledQty * avgFillPrice → 보유 자산
     * - SELL: -(filledQty * avgFillPrice) → 보유 감소
     *
     * SUM 결과는 현재 종목 순 포지션 평가액(원).
     * avgFillPrice가 null인 경우(체결 미완, 드문 케이스) COALESCE(0)으로 처리.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(
                CASE o.side
                    WHEN 'BUY'  THEN  o.filled_qty * COALESCE(o.avg_fill_price, 0)
                    WHEN 'SELL' THEN -o.filled_qty * COALESCE(o.avg_fill_price, 0)
                    ELSE 0
                END
            ), 0)
            FROM orders o
            WHERE o.user_id = :userId
              AND o.code = :code
              AND o.status IN ('FILLED','PARTIALLY_FILLED')
        """,
        nativeQuery = true,
    )
    fun sumPositionValue(
        @Param("userId") userId: UUID,
        @Param("code") code: String,
    ): BigDecimal

    /**
     * 사용자의 오늘 일일 실현 P&L 계산 (KST 기준 당일 체결 이벤트).
     *
     * SELL 체결금액 - BUY 체결금액의 당일 합계.
     * 비용(수수료·세금) 미포함 — 1차 보수적 추정.
     */
    @Query(
        value = """
            SELECT COALESCE(SUM(
                CASE o.side
                    WHEN 'SELL' THEN  o.filled_qty * COALESCE(o.avg_fill_price, 0)
                    WHEN 'BUY'  THEN -o.filled_qty * COALESCE(o.avg_fill_price, 0)
                    ELSE 0
                END
            ), 0)
            FROM orders o
            WHERE o.user_id = :userId
              AND o.status = 'FILLED'
              AND DATE(o.updated_at AT TIME ZONE 'Asia/Seoul') = CURRENT_DATE AT TIME ZONE 'Asia/Seoul'
        """,
        nativeQuery = true,
    )
    fun sumTodayRealizedPnl(
        @Param("userId") userId: UUID,
    ): BigDecimal
}

@Component
@Transactional
class OrderJpaAdapter(
    private val repo: OrderJpaRepository,
) : OrderRepository {
    override fun save(record: OrderRecord): OrderRecord = repo.save(OrderEntity.from(record)).toDomain()

    override fun findById(orderId: UUID): OrderRecord? = repo.findById(orderId).orElse(null)?.toDomain()

    override fun findByKisOrderNo(
        kisMode: String,
        kisOrderNo: String,
    ): OrderRecord? = repo.findByKisModeAndKisOrderNo(kisMode, kisOrderNo)?.toDomain()

    override fun findOpenByUser(userId: UUID): List<OrderRecord> = repo.findOpenByUser(userId).map { it.toDomain() }

    override fun updateStatus(
        orderId: UUID,
        status: OrderStatus,
        filledQty: Int,
        avgFillPrice: BigDecimal?,
    ) {
        repo.updateStatus(orderId, status, filledQty, avgFillPrice)
    }

    /** 종목 포지션 평가액 (BUY 체결합 - SELL 체결합, 원 단위). */
    fun positionValue(
        userId: UUID,
        code: String,
    ): BigDecimal = repo.sumPositionValue(userId, code)

    /** 오늘 KST 일일 실현 P&L. 수수료 미포함 보수적 추정. */
    fun todayRealizedPnl(userId: UUID): BigDecimal = repo.sumTodayRealizedPnl(userId)
}

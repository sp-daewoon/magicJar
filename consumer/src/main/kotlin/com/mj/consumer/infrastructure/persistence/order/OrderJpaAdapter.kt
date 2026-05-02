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
}

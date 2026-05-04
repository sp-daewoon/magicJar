package com.mj.api.infrastructure.persistence.order

import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.springframework.data.jpa.repository.JpaRepository
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * api 모듈의 orders 테이블 read view.
 * consumer 모듈에 OrderEntity가 있지만 api는 그 모듈에 의존하지 않으므로 자체 매핑.
 * V9·V11 Flyway schema 대응.
 *
 * V11 변경:
 * - kisOrderNo / krxFwdgOrdOrgno NULLABLE (REJECTED 주문은 KIS 미도달)
 * - rejectReasonCode / rejectDetail / rejectSource 컬럼 추가 (ADR 0034)
 */
@Entity
@Table(name = "orders")
class OrderApiEntity(
    @Id
    @Column(name = "order_id")
    val orderId: UUID,
    @Column(name = "user_id", nullable = false)
    val userId: String,
    @Column(name = "account_no", nullable = false, length = 20)
    val accountNo: String,
    @Column(name = "kis_mode", nullable = false, length = 8)
    val kisMode: String,
    @Column(name = "intent_id", nullable = false)
    val intentId: UUID,
    /** V11: NULLABLE — REJECTED 주문은 null. */
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
    /** V11: RejectReason.code. */
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
)

interface OrderApiJpaRepository : JpaRepository<OrderApiEntity, UUID> {
    fun findTop100ByUserIdOrderByCreatedAtDesc(userId: String): List<OrderApiEntity>

    fun findByUserIdAndStatusInOrderByCreatedAtDesc(
        userId: String,
        statuses: List<OrderStatus>,
    ): List<OrderApiEntity>
}

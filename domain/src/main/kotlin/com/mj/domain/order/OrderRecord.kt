package com.mj.domain.order

import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 주문 메타 (mutable projection). spec §4.1 orders 테이블 매핑.
 *
 * - intent_id = 전략 신호로부터의 OrderIntentEvent.intentId
 * - kis_order_no, krx_fwdg_ord_orgno = KIS 주문 응답 ODNO/KRX_FWDG_ORD_ORGNO
 *   (정정/취소 호출에 둘 다 필요)
 * - status는 라이프사이클 진행에 따라 UPDATE
 * - 영구 audit trail은 OrderEventRecord (append-only)
 */
data class OrderRecord(
    val orderId: UUID,
    val userId: UUID,
    val accountNo: String,
    val kisMode: String,
    val intentId: UUID,
    val kisOrderNo: String,
    val krxFwdgOrdOrgno: String,
    val code: String,
    val side: Side,
    val qty: Int,
    val price: BigDecimal?,
    val orderType: OrderType,
    val status: OrderStatus,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    val rejectReason: String?,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

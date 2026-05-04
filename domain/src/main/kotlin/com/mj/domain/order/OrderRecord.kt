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
 * - V11: kisOrderNo / krxFwdgOrdOrgno NULLABLE (REJECTED 주문은 KIS 미도달)
 * - V11: rejectReasonCode / rejectDetail / rejectSource 추가 (ADR 0034)
 */
data class OrderRecord(
    val orderId: UUID,
    val userId: String,
    val accountNo: String,
    val kisMode: String,
    val intentId: UUID,
    /** V11: KIS 주문번호. REJECTED 주문은 null. */
    val kisOrderNo: String?,
    /** V11: KRX 전송 주문조직번호. REJECTED 주문은 null. */
    val krxFwdgOrdOrgno: String?,
    val code: String,
    val side: Side,
    val qty: Int,
    val price: BigDecimal?,
    val orderType: OrderType,
    val status: OrderStatus,
    val filledQty: Int,
    val avgFillPrice: BigDecimal?,
    /** V11 이전 호환 — Guard reject 시 레거시 reason String (null 허용). RejectReason.code로 대체. */
    val rejectReason: String?,
    /** V11: RejectReason.code (KILL_SWITCH·SESSION_CLOSED 등). */
    val rejectReasonCode: String? = null,
    /** V11: RejectReason.toDetail() 상세 메시지. */
    val rejectDetail: String? = null,
    /** V11: 거부 출처 — "GUARD" | "KIS_API" | "INTERNAL". */
    val rejectSource: String? = null,
    val createdAt: OffsetDateTime,
    val updatedAt: OffsetDateTime,
)

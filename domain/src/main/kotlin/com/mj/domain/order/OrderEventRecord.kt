package com.mj.domain.order

import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

/**
 * 주문 라이프사이클 audit trail (append-only INSERT only). spec §4.1 order_events.
 *
 * 멱등 dedup 키: (kis_mode, kis_order_no, eventType, cumulativeQty)
 * — WS push와 REST sweep가 같은 체결 두 번 도착해도 한 번만 통과.
 */
enum class OrderEventType {
    PLACED,
    MODIFIED,
    CANCELED,
    EXECUTED,
    REJECTED,
    RECEIPT_ACCEPTED,
}

enum class OrderEventSource {
    WS,
    REST_SWEEP,
    INTERNAL,
}

data class OrderEventRecord(
    val eventId: UUID,
    val orderId: UUID,
    val userId: String,
    val accountNo: String,
    val kisMode: String,
    /** V11: REJECTED 이벤트는 KIS 미도달이므로 null. */
    val kisOrderNo: String?,
    val eventType: OrderEventType,
    val cumulativeQty: Int?,
    val partialQty: Int?,
    val execPrice: BigDecimal?,
    val source: OrderEventSource?,
    val payload: String?, // JSONB raw
    val occurredAt: OffsetDateTime,
    val ingestedAt: OffsetDateTime,
)

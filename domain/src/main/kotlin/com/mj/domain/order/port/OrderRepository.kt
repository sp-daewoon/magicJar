package com.mj.domain.order.port

import com.mj.domain.common.OrderStatus
import com.mj.domain.order.OrderEventRecord
import com.mj.domain.order.OrderRecord
import java.math.BigDecimal
import java.util.UUID

interface OrderRepository {
    fun save(record: OrderRecord): OrderRecord

    fun findById(orderId: UUID): OrderRecord?

    fun findByKisOrderNo(
        kisMode: String,
        kisOrderNo: String,
    ): OrderRecord?

    fun findOpenByUser(userId: UUID): List<OrderRecord>

    fun updateStatus(
        orderId: UUID,
        status: OrderStatus,
        filledQty: Int,
        avgFillPrice: BigDecimal?,
    )
}

interface OrderEventRepository {
    /**
     * INSERT 시도. UNIQUE(kis_mode, kis_order_no, event_type, cumulative_qty) 충돌 시 false (멱등).
     * 성공 시 true. spec §3 D8 + §7.2.
     */
    fun insertIdempotent(event: OrderEventRecord): Boolean

    fun findByOrder(orderId: UUID): List<OrderEventRecord>
}

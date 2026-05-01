package com.mj.consumer.application.order

import com.mj.domain.common.OrderStatus
import com.mj.domain.order.OrderRecord
import com.mj.domain.order.port.OrderRepository
import org.springframework.stereotype.Component
import java.math.BigDecimal

/**
 * orders projection 갱신 헬퍼. spec §3 D8, §7.2.
 *
 * order_events INSERT 성공(멱등 통과) 후 orders 테이블의 status·filled_qty·avg_fill_price 갱신.
 * cumulative_qty와 order.qty 비교로 PARTIALLY_FILLED / FILLED 결정.
 */
@Component
class OrderProjectionUpdater(
    private val orders: OrderRepository,
) {
    fun updateOnExecution(
        record: OrderRecord,
        cumulativeQty: Int,
        avgFillPrice: BigDecimal,
    ) {
        val status =
            when {
                cumulativeQty <= 0 -> OrderStatus.PENDING
                cumulativeQty >= record.qty -> OrderStatus.FILLED
                else -> OrderStatus.PARTIALLY_FILLED
            }
        orders.updateStatus(record.orderId, status, cumulativeQty, avgFillPrice)
    }

    fun computeStatus(
        recordQty: Int,
        cumulativeQty: Int,
    ): OrderStatus =
        when {
            cumulativeQty <= 0 -> OrderStatus.PENDING
            cumulativeQty >= recordQty -> OrderStatus.FILLED
            else -> OrderStatus.PARTIALLY_FILLED
        }
}

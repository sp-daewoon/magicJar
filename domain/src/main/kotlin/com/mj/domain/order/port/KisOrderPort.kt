package com.mj.domain.order.port

import com.mj.domain.common.Order
import com.mj.domain.order.KisBalanceSnapshot
import com.mj.domain.order.KisExecutionLine
import com.mj.domain.order.KisOrderResult
import java.math.BigDecimal
import java.time.LocalDate
import java.util.UUID

/**
 * KIS 주문/잔고/체결조회 시스템 어댑터 포트. spec §3 D1, §6.2.
 * 인프라(consumer.infrastructure.kis.RealKisOrderAdapter)에서 구현.
 */
interface KisOrderPort {
    fun placeOrder(
        userId: UUID,
        order: Order,
    ): KisOrderResult

    fun cancelOrder(
        userId: UUID,
        kisOrderNo: String,
        krxFwdgOrdOrgno: String,
        qtyAll: Boolean = true,
        qty: Int = 0,
    ): KisOrderResult

    fun modifyOrder(
        userId: UUID,
        kisOrderNo: String,
        krxFwdgOrdOrgno: String,
        newQty: Int,
        newPrice: BigDecimal,
    ): KisOrderResult

    fun fetchBalance(userId: UUID): KisBalanceSnapshot

    fun fetchExecutions(
        userId: UUID,
        date: LocalDate,
        executedOnly: Boolean = false,
    ): List<KisExecutionLine>
}

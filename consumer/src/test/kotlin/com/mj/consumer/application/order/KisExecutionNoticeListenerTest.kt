package com.mj.consumer.application.order

import com.mj.consumer.infrastructure.kis.KisExecutionNoticeRaw
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.OrderRecord
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

class KisExecutionNoticeListenerTest {
    private val orders = mockk<OrderRepository>(relaxed = true)
    private val events = mockk<OrderEventRepository>(relaxed = true)
    private val router = mockk<KisWsRouter>(relaxed = true)

    // 실 인스턴스 — computeStatus가 실 동작해야 publish에 정확한 status 반영
    private val projection = OrderProjectionUpdater(orders)
    private val publisher = mockk<OrderExecutedPublisher>(relaxed = true)
    private val listener = KisExecutionNoticeListener(orders, events, projection, publisher, router, htsId = "")

    private val orderRow =
        OrderRecord(
            orderId = UUID.fromString("00000000-0000-0000-0000-00000000aa10"),
            userId = "74683223",
            accountNo = "50185338",
            kisMode = "MOCK_KIS",
            intentId = UUID.randomUUID(),
            kisOrderNo = "0000123456",
            krxFwdgOrdOrgno = "06010",
            code = "005930",
            side = Side.BUY,
            qty = 10,
            price = BigDecimal("70000"),
            orderType = OrderType.LIMIT,
            status = OrderStatus.PENDING,
            filledQty = 0,
            avgFillPrice = null,
            rejectReason = null,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now(),
        )

    private fun raw(
        cntgYn: String,
        executedQty: Int = 5,
        unitPrice: BigDecimal = BigDecimal("70000"),
    ) = KisExecutionNoticeRaw(
        custId = "USERID",
        acntNo = "50185338",
        orderNo = "0000123456",
        originalOrderNo = "",
        sellBuyClass = "2",
        rectifyClass = "0",
        orderKind = "",
        orderCondition = "",
        stockCode = "005930",
        executedQty = executedQty,
        executedUnitPrice = unitPrice,
        executedTime = "143025",
        refusalYn = "N",
        cntgYn = cntgYn,
        acceptYn = "Y",
        krxFwdgOrdOrgno = "06010",
        orderQty = 10,
        rest = emptyList(),
    )

    @Test
    fun `WS push EXECUTED inserts order_event and updates projection`() {
        every { orders.findByKisOrderNo("MOCK_KIS", "0000123456") } returns orderRow
        every { orders.findByKisOrderNo("REAL_KIS", "0000123456") } returns null
        every { events.findByOrder(orderRow.orderId) } returns emptyList()
        every { events.insertIdempotent(any()) } returns true

        listener.onFrame(raw(cntgYn = "2", executedQty = 5))

        verify(exactly = 1) {
            events.insertIdempotent(
                match { it.eventType == OrderEventType.EXECUTED && it.cumulativeQty == 5 },
            )
        }
        verify(exactly = 1) {
            orders.updateStatus(orderRow.orderId, OrderStatus.PARTIALLY_FILLED, 5, any())
        }
        verify(exactly = 1) { publisher.publish(any()) }
    }

    @Test
    fun `WS push duplicate EXECUTED is dropped`() {
        every { orders.findByKisOrderNo(any(), any()) } returns orderRow
        every { events.findByOrder(orderRow.orderId) } returns emptyList()
        every { events.insertIdempotent(any()) } returns false // dedup hit

        listener.onFrame(raw(cntgYn = "2"))

        verify(exactly = 0) { orders.updateStatus(any(), any(), any(), any()) }
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `WS push CNTG_YN=1 inserts RECEIPT_ACCEPTED only`() {
        every { orders.findByKisOrderNo(any(), any()) } returns orderRow
        every { events.insertIdempotent(any()) } returns true

        listener.onFrame(raw(cntgYn = "1"))

        verify(exactly = 1) {
            events.insertIdempotent(match { it.eventType == OrderEventType.RECEIPT_ACCEPTED })
        }
        verify(exactly = 0) { orders.updateStatus(any(), any(), any(), any()) }
    }

    @Test
    fun `unknown order_no is logged and skipped`() {
        every { orders.findByKisOrderNo(any(), any()) } returns null
        listener.onFrame(raw(cntgYn = "2"))
        verify(exactly = 0) { events.insertIdempotent(any()) }
    }

    @Test
    fun `partial then full execution accumulates correctly`() {
        // 1차 push 5주 → 누적 5
        every { orders.findByKisOrderNo("MOCK_KIS", "0000123456") } returns orderRow
        every { orders.findByKisOrderNo("REAL_KIS", "0000123456") } returns null
        every { events.findByOrder(orderRow.orderId) } returns emptyList()
        every { events.insertIdempotent(any()) } returns true
        val captured = slot<OrderExecutedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        listener.onFrame(raw(cntgYn = "2", executedQty = 5))
        captured.captured.executedQty shouldBe 5
        captured.captured.status shouldBe OrderStatus.PARTIALLY_FILLED
    }
}

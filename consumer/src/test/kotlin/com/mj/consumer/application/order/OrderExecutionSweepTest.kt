package com.mj.consumer.application.order

import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.order.KisExecutionLine
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.OrderRecord
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.port.KisOrderPort
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

class OrderExecutionSweepTest {
    private val kisPort = mockk<KisOrderPort>()
    private val orders = mockk<OrderRepository>(relaxed = true)
    private val events = mockk<OrderEventRepository>(relaxed = true)
    private val projection = OrderProjectionUpdater(orders)
    private val publisher = mockk<OrderExecutedPublisher>(relaxed = true)
    private val sweep = OrderExecutionSweep(kisPort, orders, events, projection, publisher)

    private val orderRow =
        OrderRecord(
            orderId = UUID.fromString("00000000-0000-0000-0000-00000000aa10"),
            userId = KisOrderSender.DEFAULT_USER_ID,
            accountNo = "50185338",
            kisMode = "MOCK_KIS",
            intentId = UUID.randomUUID(),
            kisOrderNo = "0000123456",
            krxFwdgOrdOrgno = "06010",
            code = "005930",
            side = Side.BUY,
            qty = 1,
            price = BigDecimal("70000"),
            orderType = OrderType.LIMIT,
            status = OrderStatus.PENDING,
            filledQty = 0,
            avgFillPrice = null,
            rejectReason = null,
            createdAt = OffsetDateTime.now(),
            updatedAt = OffsetDateTime.now(),
        )

    @Test
    fun `sweep ingests new executions and publishes FILLED`() {
        every { orders.findOpenByUser(KisOrderSender.DEFAULT_USER_ID) } returns listOf(orderRow)
        every { kisPort.fetchExecutions(any(), any(), any()) } returns
            listOf(
                KisExecutionLine(
                    orderDate = "20260502",
                    kisOrderNo = "0000123456",
                    originalOrderNo = "",
                    krxFwdgOrdOrgno = "06010",
                    code = "005930",
                    side = Side.BUY,
                    orderedQty = 1,
                    orderedPrice = BigDecimal("70000"),
                    cumulativeExecutedQty = 1,
                    cumulativeExecutedAmount = BigDecimal("70000"),
                    canceled = false,
                    remainingQty = 0,
                    rejectedQty = 0,
                ),
            )
        every { events.insertIdempotent(any()) } returns true
        val captured = slot<OrderExecutedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        sweep.run()

        verify(exactly = 1) {
            events.insertIdempotent(
                match {
                    it.eventType == OrderEventType.EXECUTED && it.cumulativeQty == 1
                },
            )
        }
        verify(exactly = 1) {
            orders.updateStatus(orderRow.orderId, OrderStatus.FILLED, 1, any())
        }
        captured.captured.status shouldBe OrderStatus.FILLED
    }

    @Test
    fun `sweep ignores duplicates (insertIdempotent false)`() {
        every { orders.findOpenByUser(any()) } returns listOf(orderRow)
        every { kisPort.fetchExecutions(any(), any(), any()) } returns
            listOf(
                KisExecutionLine(
                    "20260502",
                    "0000123456",
                    "",
                    "06010",
                    "005930",
                    Side.BUY,
                    1,
                    BigDecimal("70000"),
                    1,
                    BigDecimal("70000"),
                    false,
                    0,
                    0,
                ),
            )
        every { events.insertIdempotent(any()) } returns false

        sweep.run()

        verify(exactly = 0) { orders.updateStatus(any(), any(), any(), any()) }
        verify(exactly = 0) { publisher.publish(any()) }
    }

    @Test
    fun `sweep skips lines whose cumulative is not greater than current filled`() {
        // orderRow.filledQty=0, line.cumulativeExecutedQty=0 → skip
        every { orders.findOpenByUser(any()) } returns listOf(orderRow)
        every { kisPort.fetchExecutions(any(), any(), any()) } returns
            listOf(
                KisExecutionLine(
                    "20260502",
                    "0000123456",
                    "",
                    "06010",
                    "005930",
                    Side.BUY,
                    1,
                    BigDecimal("70000"),
                    0,
                    BigDecimal.ZERO,
                    false,
                    1,
                    0,
                ),
            )

        sweep.run()
        verify(exactly = 0) { events.insertIdempotent(any()) }
    }

    @Test
    fun `sweep does nothing when no open orders`() {
        every { orders.findOpenByUser(any()) } returns emptyList()
        sweep.run()
        verify(exactly = 0) { kisPort.fetchExecutions(any(), any(), any()) }
    }
}

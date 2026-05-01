package com.mj.consumer.messaging.listener

import com.mj.consumer.messaging.producer.OrderExecutedPublisher
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.order.event.OrderExecutedEvent
import com.mj.domain.order.event.OrderIntentEvent
import com.mj.domain.order.port.OrderSender
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class OrderDispatcherListenerTest {
    private val sender = mockk<OrderSender>()
    private val publisher = mockk<OrderExecutedPublisher>(relaxed = true)
    private val listener = OrderDispatcherListener(sender, publisher)

    private fun intent() =
        OrderIntentEvent(
            intentId = UUID.randomUUID(),
            strategyId = "MA_X",
            signalId = UUID.randomUUID(),
            code = "005930",
            side = Side.BUY,
            qty = 1,
            price = BigDecimal("70000"),
            orderType = OrderType.LIMIT,
            mode = "MOCK_KIS",
            createdAt = Instant.now(),
        )

    @Test
    fun `mock-style FILLED result publishes executed FILLED event`() {
        val ev = intent()
        every { sender.send(any()) } answers {
            val o = arg<com.mj.domain.common.Order>(0)
            OrderResult(
                order = o,
                resultStatus = OrderStatus.FILLED,
                filledQty = o.qty,
                avgFillPrice = o.price,
                filledAt = Instant.now(),
                rejectReason = null,
            )
        }
        val captured = slot<OrderExecutedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        listener.onOrderIntent(ev)

        captured.captured.status shouldBe OrderStatus.FILLED
        captured.captured.executedQty shouldBe 1
        captured.captured.intentId shouldBe ev.intentId
    }

    @Test
    fun `kis-style PENDING result publishes executed PENDING event`() {
        val ev = intent()
        every { sender.send(any()) } answers {
            val o = arg<com.mj.domain.common.Order>(0)
            OrderResult(
                order = o,
                resultStatus = OrderStatus.PENDING,
                filledQty = 0,
                avgFillPrice = null,
                filledAt = null,
                rejectReason = null,
            )
        }
        val captured = slot<OrderExecutedEvent>()
        every { publisher.publish(capture(captured)) } returns Unit

        listener.onOrderIntent(ev)

        captured.captured.status shouldBe OrderStatus.PENDING
        captured.captured.executedQty shouldBe 0
    }

    @Test
    fun `OrderSender exception is swallowed and no publish happens`() {
        every { sender.send(any()) } throws RuntimeException("kis down")
        listener.onOrderIntent(intent())
        verify(exactly = 0) { publisher.publish(any()) }
    }
}

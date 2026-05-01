package com.mj.consumer.application.order

import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class MockOrderSenderTest {
    private val sender = MockOrderSender()

    @Test
    fun `mock sender returns FILLED instantly`() {
        val order =
            Order(
                id = UUID.randomUUID(),
                code = "005930",
                side = Side.BUY,
                qty = 5,
                price = BigDecimal("70000"),
                type = OrderType.LIMIT,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(),
            )
        val r = sender.send(order)
        r.resultStatus shouldBe OrderStatus.FILLED
        r.filledQty shouldBe 5
        r.avgFillPrice shouldBe BigDecimal("70000")
    }

    @Test
    fun `mock sender for MARKET uses zero price`() {
        val order =
            Order(
                id = UUID.randomUUID(),
                code = "005930",
                side = Side.SELL,
                qty = 1,
                price = null,
                type = OrderType.MARKET,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(),
            )
        val r = sender.send(order)
        r.resultStatus shouldBe OrderStatus.FILLED
        r.avgFillPrice shouldBe BigDecimal.ZERO
    }
}

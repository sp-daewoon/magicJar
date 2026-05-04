package com.mj.api.messaging

import com.mj.api.messaging.listener.OrderbookStompFanoutListener
import com.mj.domain.market.event.OrderbookEvent
import com.mj.domain.market.event.OrderbookLevel
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.Instant

class OrderbookStompFanoutListenerTest {
    private val stomp = mockk<SimpMessagingTemplate>(relaxed = true)
    private val listener = OrderbookStompFanoutListener(stomp)

    @Test
    fun `orderbook event → STOMP topic market orderbook code fanout`() {
        every { stomp.convertAndSend(any<String>(), any<Any>()) } just Runs

        val asks = (0 until 10).map { OrderbookLevel(BigDecimal(75000 + it * 100), 1000L + it * 100) }
        val bids = (0 until 10).map { OrderbookLevel(BigDecimal(74900 - it * 100), 2000L + it * 100) }
        val event =
            OrderbookEvent(
                code = "005930",
                at = Instant.now(),
                asks = asks,
                bids = bids,
                totalAskQty = 55_000L,
                totalBidQty = 65_000L,
            )

        listener.onOrderbook(event)

        verify(exactly = 1) { stomp.convertAndSend("/topic/market.orderbook.005930", event) }
    }
}

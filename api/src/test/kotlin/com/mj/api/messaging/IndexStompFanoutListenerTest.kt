package com.mj.api.messaging

import com.mj.api.messaging.listener.IndexStompFanoutListener
import com.mj.domain.market.event.IndexTickEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.messaging.simp.SimpMessagingTemplate
import java.math.BigDecimal
import java.time.Instant

/**
 * IndexStompFanoutListener 단위 테스트 — plan T2-R2-A2 §T15.
 */
class IndexStompFanoutListenerTest {
    private val stomp = mockk<SimpMessagingTemplate>(relaxed = true)
    private val listener = IndexStompFanoutListener(stomp)

    @Test
    fun `KOSPI tick → topic market index 0001 fanout`() {
        every { stomp.convertAndSend(any<String>(), any<Any>()) } just Runs

        val event =
            IndexTickEvent(
                code = IndexTickEvent.KOSPI,
                at = Instant.parse("2026-05-04T05:30:00Z"),
                value = BigDecimal("2585.43"),
                change = BigDecimal("12.50"),
                changeRate = BigDecimal("0.49"),
                volume = 120_000_000L,
                turnover = 8_500_000_000_000L,
            )

        listener.onIndexTick(event)

        verify(exactly = 1) { stomp.convertAndSend("/topic/market.index.0001", event) }
    }

    @Test
    fun `KOSDAQ tick → topic market index 1001 fanout`() {
        every { stomp.convertAndSend(any<String>(), any<Any>()) } just Runs

        val event =
            IndexTickEvent(
                code = IndexTickEvent.KOSDAQ,
                at = Instant.now(),
                value = BigDecimal("845.21"),
                change = BigDecimal("-3.42"),
                changeRate = BigDecimal("-0.40"),
                volume = 50_000_000L,
                turnover = 1_200_000_000_000L,
            )

        listener.onIndexTick(event)

        verify(exactly = 1) { stomp.convertAndSend("/topic/market.index.1001", event) }
    }
}

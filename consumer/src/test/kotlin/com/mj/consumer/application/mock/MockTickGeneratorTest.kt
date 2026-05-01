package com.mj.consumer.application.mock

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertDoesNotThrow

class MockTickGeneratorTest {
    private val publisher: TickPublisher = mockk(relaxed = true)

    @Test
    fun `init 후 emit 1회 호출 시 모든 symbol에 대해 publish가 호출된다`() {
        val props =
            MockTickProperties(
                enabled = true,
                intervalMs = 1000,
                symbols = listOf("005930", "000660"),
                basePrices = mapOf("005930" to 60_000L, "000660" to 130_000L),
                volatilityPercent = 0.5,
            )
        val gen = MockTickGenerator(publisher, props)
        gen.init()

        gen.emit()

        verify(exactly = 2) { publisher.publish(any()) }
    }

    @Test
    fun `생성된 가격은 base의 ±10 percent 범위 안이다`() {
        val props =
            MockTickProperties(
                enabled = true,
                symbols = listOf("005930"),
                basePrices = mapOf("005930" to 60_000L),
                volatilityPercent = 1.0,
            )
        val gen = MockTickGenerator(publisher, props)
        gen.init()

        val captured = slot<TickEvent>()
        io.mockk.every { publisher.publish(capture(captured)) } returns Unit

        // 100 tick 발행 — 클램프 보장 검증
        repeat(100) { gen.emit() }

        val price = captured.captured.price.toLong()
        assertTrue(price in 54_000L..66_000L) { "price out of clamp: $price" }
        assertEquals("005930", captured.captured.code)
        assertEquals("MOCK", captured.captured.source)
        assertNotNull(captured.captured.at)
    }

    @Test
    fun `symbols 비어있으면 emit이 publish를 호출하지 않는다`() {
        val props = MockTickProperties(enabled = true, symbols = emptyList())
        val gen = MockTickGenerator(publisher, props)
        gen.init()

        assertDoesNotThrow { gen.emit() }

        verify(exactly = 0) { publisher.publish(any()) }
    }
}

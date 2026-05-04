package com.mj.consumer.messaging.listener

import com.mj.consumer.application.market.QuoteResolverService
import com.mj.domain.market.event.TickEvent
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * TickQuoteCacheWriterListener 단위 테스트 — Quote hotfix B (R-A).
 */
class TickQuoteCacheWriterListenerTest {
    private val resolver = mockk<QuoteResolverService>()
    private val listener = TickQuoteCacheWriterListener(resolver)

    @Test
    fun `tick 수신 시 QuoteResolver putLatest 호출`() {
        val event =
            TickEvent(
                code = "005930",
                at = Instant.parse("2026-05-04T05:00:00Z"),
                price = BigDecimal("75000"),
                volume = 100,
                source = "KIS_WS",
                stale = false,
                asOf = null,
            )
        every { resolver.putLatest(event) } just Runs

        listener.onTick(event)

        verify(exactly = 1) { resolver.putLatest(event) }
    }

    @Test
    fun `putLatest 예외 시 메시지 손실 허용 (rethrow 안 함)`() {
        val event =
            TickEvent(
                code = "005930",
                at = Instant.now(),
                price = BigDecimal("75000"),
                volume = 0,
            )
        every { resolver.putLatest(any()) } throws RuntimeException("redis down")

        // 예외 swallowed — 호출자(@KafkaListener)에게 전파되지 않아야 한다
        listener.onTick(event)

        verify(exactly = 1) { resolver.putLatest(event) }
    }
}

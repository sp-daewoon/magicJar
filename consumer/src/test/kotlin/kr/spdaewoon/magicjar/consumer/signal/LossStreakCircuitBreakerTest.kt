package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.notification.port.NotificationRateLimiterPort
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * 진입 가드 ③ LossStreakCircuitBreaker 단위 테스트 (2026-07-08).
 *
 * 연속 N패 → BUY_BLOCKED 전환·이벤트 emit / 승 리셋 / 무손익 불변 / 전환 후 reset / 토글 off·sentinel no-op 을 고정한다.
 */
class LossStreakCircuitBreakerTest {
    private val writer = mockk<LossStreakBuyBlockWriter>(relaxed = true)
    private val rateLimiter = mockk<NotificationRateLimiterPort>()
    private val eventLogger = mockk<SystemEventLogger>(relaxed = true)

    private fun breaker(
        enabled: Boolean = true,
        threshold: Int = 4,
        limiterAllows: Boolean = true,
    ): LossStreakCircuitBreaker {
        every { rateLimiter.shouldEmitOncePerWindow(any(), any(), any()) } returns limiterAllows
        return LossStreakCircuitBreaker(
            buyBlockWriterProvider = providerOf(writer),
            rateLimiterProvider = providerOf(rateLimiter),
            systemEventLoggerProvider = providerOf(eventLogger),
            enabled = enabled,
            lossStreakThreshold = threshold,
        )
    }

    private fun loss() = BigDecimal("-5000")

    private fun win() = BigDecimal("7000")

    @Test
    fun `연속 4패 도달 시 BUY_BLOCKED 전환 + 이벤트 emit`() {
        val b = breaker(threshold = 4)
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
        b.onRoundTripClosed(105L, "1234", "014910", loss()) // 4th consecutive loss
        verify(exactly = 1) { writer.blockBuy(105L, "RISK_VIOLATION") }
        verify(exactly = 1) { eventLogger.emit(any<SystemEvent>()) }
    }

    @Test
    fun `3패까지는 미발동`() {
        val b = breaker(threshold = 4)
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
    }

    @Test
    fun `승이 연속 패를 리셋한다`() {
        val b = breaker(threshold = 4)
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        b.onRoundTripClosed(105L, "1234", "014910", win()) // reset
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) } // 3 more → still 3
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
        b.onRoundTripClosed(105L, "1234", "014910", loss()) // now 4th consecutive
        verify(exactly = 1) { writer.blockBuy(105L, "RISK_VIOLATION") }
    }

    @Test
    fun `무손익(0) 청산은 카운터 불변`() {
        val b = breaker(threshold = 4)
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        b.onRoundTripClosed(105L, "1234", "014910", BigDecimal.ZERO) // neutral — no change
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
        b.onRoundTripClosed(105L, "1234", "014910", loss()) // 4th
        verify(exactly = 1) { writer.blockBuy(105L, "RISK_VIOLATION") }
    }

    @Test
    fun `전환 후 streak reset — 즉시 재발동 안 함`() {
        val b = breaker(threshold = 4)
        repeat(4) { b.onRoundTripClosed(105L, "1234", "014910", loss()) } // trips once
        verify(exactly = 1) { writer.blockBuy(any(), any()) }
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) } // 3 more after reset
        verify(exactly = 1) { writer.blockBuy(any(), any()) } // still once
    }

    @Test
    fun `토글 off면 no-op`() {
        val b = breaker(enabled = false, threshold = 4)
        repeat(10) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
    }

    @Test
    fun `threshold 0이면 비활성 no-op`() {
        val b = breaker(threshold = 0)
        repeat(10) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
    }

    @Test
    fun `MANUAL sentinel(strategyId less than or equal 0) no-op`() {
        val b = breaker(threshold = 4)
        repeat(10) { b.onRoundTripClosed(0L, "1234", "014910", loss()) }
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
    }

    @Test
    fun `전략별 카운터 격리 — 다른 전략 패는 서로 무관`() {
        val b = breaker(threshold = 4)
        repeat(3) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        repeat(3) { b.onRoundTripClosed(106L, "1234", "037070", loss()) }
        verify(exactly = 0) { writer.blockBuy(any(), any()) }
    }

    @Test
    fun `limiter가 막으면 이벤트 emit 안 하지만 BUY_BLOCKED는 전환`() {
        val b = breaker(threshold = 4, limiterAllows = false)
        repeat(4) { b.onRoundTripClosed(105L, "1234", "014910", loss()) }
        verify(exactly = 1) { writer.blockBuy(105L, "RISK_VIOLATION") }
        verify(exactly = 0) { eventLogger.emit(any<SystemEvent>()) }
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }
}

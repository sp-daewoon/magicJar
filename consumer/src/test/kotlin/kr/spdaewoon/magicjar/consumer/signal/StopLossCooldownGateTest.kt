package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * 진입 가드 ② StopLossCooldownGate 단위 테스트 (2026-07-08).
 *
 * 손절 청산 기록 → 쿨다운 조회 true / 미기록 false / 토글 off no-op / fail-open 을 고정한다.
 */
class StopLossCooldownGateTest {
    private val redis = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()

    private fun gate(
        enabled: Boolean = true,
        minutes: Long = 60,
    ): StopLossCooldownGate {
        every { redis.opsForValue() } returns valueOps
        return StopLossCooldownGate(redisTemplate = redis, enabled = enabled, cooldownMinutes = minutes)
    }

    @Test
    fun `손절 청산 기록 시 TTL=윈도우로 set 호출`() {
        every { valueOps.set(any(), any(), any<Duration>()) } just Runs
        gate().recordStopLossExit("1234", "014910")
        verify(exactly = 1) {
            valueOps.set("strategy:sl-cooldown:user:1234:code:014910", any(), Duration.ofMinutes(60))
        }
    }

    @Test
    fun `쿨다운 키 존재하면 isInCooldown true`() {
        every { redis.hasKey("strategy:sl-cooldown:user:1234:code:014910") } returns true
        assertThat(gate().isInCooldown("1234", "014910")).isTrue()
    }

    @Test
    fun `쿨다운 키 없으면 isInCooldown false`() {
        every { redis.hasKey(any()) } returns false
        assertThat(gate().isInCooldown("1234", "014910")).isFalse()
    }

    @Test
    fun `토글 off면 기록도 조회도 no-op — redis 미접근`() {
        val g = gate(enabled = false)
        g.recordStopLossExit("1234", "014910")
        assertThat(g.isInCooldown("1234", "014910")).isFalse()
        verify(exactly = 0) { valueOps.set(any(), any(), any<Duration>()) }
        verify(exactly = 0) { redis.hasKey(any()) }
    }

    @Test
    fun `minutes 0이면 비활성 no-op`() {
        val g = gate(minutes = 0)
        g.recordStopLossExit("1234", "014910")
        assertThat(g.isInCooldown("1234", "014910")).isFalse()
        verify(exactly = 0) { redis.hasKey(any()) }
    }

    @Test
    fun `Redis 조회 예외면 fail-open false`() {
        every { redis.hasKey(any()) } throws RuntimeException("redis down")
        assertThat(gate().isInCooldown("1234", "014910")).isFalse()
    }

    @Test
    fun `Redis 기록 예외면 graceful — 체결 흐름 무손상`() {
        every { valueOps.set(any(), any(), any<Duration>()) } throws RuntimeException("redis down")
        // 예외를 삼켜야 한다(체결 권위 무손상).
        gate().recordStopLossExit("1234", "014910")
    }
}

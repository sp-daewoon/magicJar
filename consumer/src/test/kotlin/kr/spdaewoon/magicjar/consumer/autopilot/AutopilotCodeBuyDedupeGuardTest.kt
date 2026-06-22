package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * [AutopilotCodeBuyDedupeGuard] 단위 테스트 (개선 C — autopilot 종목 충돌 dedup, 2026-06-22).
 *
 * ## 검증 매트릭스 (종목 분산 정신)
 *  (a) 같은 code 첫 진입(미보유 + SETNX true) → Proceed(이 슬롯 1건만 진행).
 *  (b) 같은 cycle 타 autopilot 전략이 같은 code 선점(SETNX false) → Suppress(나머지는 분산 dedup).
 *  (c) autopilot 이 이미 그 code 보유 → Suppress(재적층 회피 — lock 잡지 않음). USER 보유는 owner-scoped 쿼리로 제외.
 *  (d) 서로 다른 code → 각각 독립 lock → 모두 Proceed(서로 다른 종목으로 분산).
 *  (e) Redis 장애 → fail-open Proceed(dedup 결함이 정상 신호를 막지 않음 — 분산 최적화 ≠ 자본 게이트).
 *  (f) 보유 조회 실패 → fail-open(lock 단계 진행).
 */
class AutopilotCodeBuyDedupeGuardTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>()
    private val repository = mockk<StrategyPositionJpaRepository>()

    private val ttl = Duration.ofSeconds(20)
    private val userId = "u1"

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    private fun guard() = AutopilotCodeBuyDedupeGuard(redisTemplate, repository)

    private fun lockKey(code: String) = "autopilot:buy:code:user:$userId:code:$code"

    @Test
    fun `(a) 미보유 + SETNX true — 첫 진입 Proceed`() {
        every { repository.findAutopilotHeldCodesByUser(userId) } returns emptyList()
        every { valueOps.setIfAbsent(lockKey("006340"), "100", ttl) } returns true

        val decision = guard().evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotCodeBuyDedupeGuard.Decision.Proceed)
    }

    @Test
    fun `(b) 같은 cycle 타 전략 선점 — SETNX false → Suppress (분산 dedup)`() {
        every { repository.findAutopilotHeldCodesByUser(userId) } returns emptyList()
        // momentum(100) 이 먼저 lock 선점 → volume-spike(104) 는 SETNX false.
        every { valueOps.setIfAbsent(lockKey("006340"), "104", ttl) } returns false

        val decision = guard().evaluate(userId, "006340", strategyId = 104L)

        assertThat(decision).isInstanceOf(AutopilotCodeBuyDedupeGuard.Decision.Suppress::class.java)
        assertThat((decision as AutopilotCodeBuyDedupeGuard.Decision.Suppress).reason)
            .contains("AUTOPILOT_CODE_CYCLE_DEDUP")
    }

    @Test
    fun `(c) autopilot 이미 보유 code — Suppress (재적층 회피, lock 미시도)`() {
        every { repository.findAutopilotHeldCodesByUser(userId) } returns listOf("051910", "006340")

        val decision = guard().evaluate(userId, "006340", strategyId = 105L)

        assertThat(decision).isInstanceOf(AutopilotCodeBuyDedupeGuard.Decision.Suppress::class.java)
        assertThat((decision as AutopilotCodeBuyDedupeGuard.Decision.Suppress).reason)
            .contains("AUTOPILOT_CODE_ALREADY_HELD")
        // 보유 종목은 lock 을 잡을 필요가 없다(SETNX 호출 0).
        io.mockk.verify(exactly = 0) { valueOps.setIfAbsent(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(d) 서로 다른 code — 각각 독립 lock → 모두 Proceed (종목 분산)`() {
        every { repository.findAutopilotHeldCodesByUser(userId) } returns emptyList()
        every { valueOps.setIfAbsent(lockKey("006340"), "100", ttl) } returns true
        every { valueOps.setIfAbsent(lockKey("080220"), "101", ttl) } returns true

        val g = guard()
        val d1 = g.evaluate(userId, "006340", strategyId = 100L)
        val d2 = g.evaluate(userId, "080220", strategyId = 101L)

        assertThat(d1).isEqualTo(AutopilotCodeBuyDedupeGuard.Decision.Proceed)
        assertThat(d2).isEqualTo(AutopilotCodeBuyDedupeGuard.Decision.Proceed)
    }

    @Test
    fun `(e) Redis 장애 — fail-open Proceed (dedup 결함이 정상 신호를 막지 않음)`() {
        every { repository.findAutopilotHeldCodesByUser(userId) } returns emptyList()
        every { valueOps.setIfAbsent(lockKey("006340"), "100", ttl) } throws
            RedisConnectionFailureException("redis down")

        val decision = guard().evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotCodeBuyDedupeGuard.Decision.Proceed)
    }

    @Test
    fun `(f) 보유 조회 실패 — fail-open (lock 단계로 진행)`() {
        every { repository.findAutopilotHeldCodesByUser(userId) } throws RuntimeException("db error")
        every { valueOps.setIfAbsent(lockKey("006340"), "100", ttl) } returns true

        val decision = guard().evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotCodeBuyDedupeGuard.Decision.Proceed)
    }
}

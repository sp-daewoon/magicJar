package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.data.redis.RedisConnectionFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.ValueOperations
import java.time.Duration

/**
 * [AutopilotReentryCooldownGuard] 단위 테스트 (follow-up I — 재진입 churn 억제, 2026-06-22).
 *
 * ## 검증 매트릭스 (churn 비용 회수 vs 정당 재진입 보존)
 *  evaluate:
 *   (a) 쿨다운 키 살아있음(윈도우 내) → Suppress(churn 억제 — 청산 직후 재매수 차단).
 *   (b) 쿨다운 키 없음(윈도우 경과·미청산) → Proceed(정당 추세 재진입 허용).
 *   (c) 다른 code → 독립 키 → 영향 없음(한 종목 쿨다운이 다른 종목 막지 않음).
 *   (d) enabled=false → Proceed(zero-regression, Redis 조회 0).
 *   (e) 윈도우 ≤ 0 → Proceed(쿨다운 비활성, Redis 조회 0).
 *   (f) Redis 장애 → fail-open Proceed(쿨다운 결함이 정상 신호를 막지 않음 — 비용 최적화 ≠ 자본 게이트).
 *  recordLiquidation:
 *   (g) owner=AUTOPILOT + 활성 → 쿨다운 키 set(TTL=윈도우).
 *   (h) owner≠AUTOPILOT(USER) → 기록 0(격리 LOCK — USER 무영향).
 *   (i) enabled=false → 기록 0 + owner 조회 0(zero-regression).
 *   (j) strategyId=0(MANUAL sentinel) → 기록 0.
 *   (k) Redis set 장애 → graceful(체결 권위 무손상, 예외 전파 0).
 */
class AutopilotReentryCooldownGuardTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>()

    private val userId = "u1"
    private val window15 = Duration.ofMinutes(15)

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    private fun guard(
        enabled: Boolean = true,
        cooldownMinutes: Long = 15L,
    ) = AutopilotReentryCooldownGuard(
        redisTemplate = redisTemplate,
        properties = MagicjarAutopilotProperties(enabled = enabled, reentryCooldownMinutes = cooldownMinutes),
        strategyRepository = strategyRepo,
    )

    private fun cooldownKey(code: String) = "autopilot:reentry:user:$userId:code:$code"

    // ── evaluate ──────────────────────────────────────────────────────────────────────────────────────

    @Test
    fun `(a) 쿨다운 윈도우 내 — Suppress (churn 억제)`() {
        every { redisTemplate.hasKey(cooldownKey("006340")) } returns true

        val decision = guard().evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isInstanceOf(AutopilotReentryCooldownGuard.Decision.Suppress::class.java)
        assertThat((decision as AutopilotReentryCooldownGuard.Decision.Suppress).reason)
            .contains("AUTOPILOT_REENTRY_COOLDOWN")
    }

    @Test
    fun `(b) 쿨다운 키 없음(윈도우 경과) — Proceed (정당 재진입 허용)`() {
        every { redisTemplate.hasKey(cooldownKey("006340")) } returns false

        val decision = guard().evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    @Test
    fun `(c) 다른 code 는 독립 — 한 종목 쿨다운이 다른 종목 막지 않음`() {
        every { redisTemplate.hasKey(cooldownKey("006340")) } returns true
        every { redisTemplate.hasKey(cooldownKey("080220")) } returns false

        val g = guard()
        assertThat(g.evaluate(userId, "006340", 100L))
            .isInstanceOf(AutopilotReentryCooldownGuard.Decision.Suppress::class.java)
        assertThat(g.evaluate(userId, "080220", 101L))
            .isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    @Test
    fun `(d) enabled=false — Proceed + Redis 조회 0 (zero-regression)`() {
        val decision = guard(enabled = false).evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
        verify(exactly = 0) { redisTemplate.hasKey(any()) }
    }

    @Test
    fun `(e) 윈도우 0 이하 — Proceed + Redis 조회 0 (쿨다운 비활성)`() {
        val decision = guard(cooldownMinutes = 0L).evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
        verify(exactly = 0) { redisTemplate.hasKey(any()) }
    }

    @Test
    fun `(f) Redis 장애 — fail-open Proceed (정상 신호 막지 않음)`() {
        every { redisTemplate.hasKey(cooldownKey("006340")) } throws RedisConnectionFailureException("redis down")

        val decision = guard().evaluate(userId, "006340", strategyId = 100L)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    // ── recordLiquidation ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `(g) owner=AUTOPILOT 전량 청산 — 쿨다운 키 기록(TTL=윈도우)`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } returns 1L

        guard().recordLiquidation(userId, "006340", strategyId = 100L)

        verify(exactly = 1) { valueOps.set(cooldownKey("006340"), any<String>(), window15) }
    }

    @Test
    fun `(h) owner≠AUTOPILOT(USER) — 기록 0 (격리 LOCK, USER 무영향)`() {
        every { strategyRepo.countAutopilotStrategyById(200L) } returns 0L

        guard().recordLiquidation(userId, "006340", strategyId = 200L)

        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(i) enabled=false — 기록 0 + owner 조회 0 (zero-regression)`() {
        guard(enabled = false).recordLiquidation(userId, "006340", strategyId = 100L)

        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(j) strategyId=0(MANUAL sentinel) — 기록 0`() {
        guard().recordLiquidation(userId, "006340", strategyId = 0L)

        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(k) Redis set 장애 — graceful (체결 권위 무손상, 예외 전파 0)`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } returns 1L
        every { valueOps.set(cooldownKey("006340"), any<String>(), window15) } throws
            RedisConnectionFailureException("redis down")

        // 예외가 전파되면 체결 트랜잭션이 깨진다 — graceful 흡수 검증(throw 없이 반환).
        guard().recordLiquidation(userId, "006340", strategyId = 100L)
    }

    @Test
    fun `(l) owner 조회 실패 — 기록 0 (graceful, USER 오기록 회피)`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } throws RuntimeException("db error")

        guard().recordLiquidation(userId, "006340", strategyId = 100L)

        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }
}

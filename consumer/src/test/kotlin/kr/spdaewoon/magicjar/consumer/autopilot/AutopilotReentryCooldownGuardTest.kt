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
import java.math.BigDecimal
import java.time.Duration

/**
 * [AutopilotReentryCooldownGuard] 단위 테스트 (follow-up I churn 억제 + ADR 0129 레버 3 가격 축).
 *
 * ## 검증 매트릭스
 *  evaluate(시간 축 — follow-up I):
 *   (a) 쿨다운 키 살아있음(윈도우 내) → Suppress(churn 억제 — 청산 직후 재매수 차단).
 *   (b) 쿨다운 키 없음(윈도우 경과·미청산) → Proceed(정당 추세 재진입 허용).
 *   (c) 다른 code → 독립 키 → 영향 없음.
 *   (d) enabled=false + pct=0 → Proceed(zero-regression, Redis 조회 0).
 *   (e) 윈도우 ≤ 0 + pct=0 → Proceed(쿨다운·가격 축 모두 비활성, Redis 조회 0).
 *   (f) Redis 장애 → fail-open Proceed.
 *  evaluate(가격 축 — ADR 0129 레버 3, 시간 축 비활성 cooldownMinutes=0 + pct>0):
 *   (m) 직전 청산가 11,070 → 현재가 12,340(+11.5%) → Suppress(추격 차단, pct=1.0).
 *   (n) 직전 청산가 11,070 → 현재가 11,000(더 쌈) → Proceed.
 *   (o) pct=0 → 가격 축 비활성 → Proceed.
 *   (p) legacy ts-only 값(price 없음) → fail-open Proceed.
 *  recordLiquidation:
 *   (g) owner=AUTOPILOT + 활성 → 쿨다운 키 `ts:price` set(TTL=윈도우).
 *   (h) owner≠AUTOPILOT(USER) → 기록 0(격리 LOCK).
 *   (i) enabled=false → 기록 0 + owner 조회 0(zero-regression).
 *   (j) strategyId=0(MANUAL sentinel) → 기록 0.
 *   (k) Redis set 장애 → graceful(예외 전파 0).
 *   (l) owner 조회 실패 → 기록 0(graceful).
 */
class AutopilotReentryCooldownGuardTest {
    private val redisTemplate = mockk<StringRedisTemplate>()
    private val valueOps = mockk<ValueOperations<String, String>>(relaxed = true)
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>()

    private val userId = "u1"
    private val window15 = Duration.ofMinutes(15)
    private val price = BigDecimal("12000")

    init {
        every { redisTemplate.opsForValue() } returns valueOps
    }

    private fun guard(
        enabled: Boolean = true,
        cooldownMinutes: Long = 15L,
        priceGuardPct: BigDecimal = BigDecimal.ZERO,
    ) = AutopilotReentryCooldownGuard(
        redisTemplate = redisTemplate,
        properties =
            MagicjarAutopilotProperties(
                enabled = enabled,
                reentryCooldownMinutes = cooldownMinutes,
                reentryPriceGuardPct = priceGuardPct,
            ),
        strategyRepository = strategyRepo,
    )

    private fun cooldownKey(code: String) = "autopilot:reentry:user:$userId:code:$code"

    private fun recorded(
        ts: Long,
        exitPrice: String,
    ) = "$ts:$exitPrice"

    // ── evaluate 시간 축 ──────────────────────────────────────────────────────────────────────────────

    @Test
    fun `(a) 쿨다운 윈도우 내 — Suppress (churn 억제)`() {
        every { valueOps.get(cooldownKey("006340")) } returns recorded(1L, "11070")

        val decision = guard().evaluate(userId, "006340", strategyId = 100L, currentPrice = price)

        assertThat(decision).isInstanceOf(AutopilotReentryCooldownGuard.Decision.Suppress::class.java)
        assertThat((decision as AutopilotReentryCooldownGuard.Decision.Suppress).reason)
            .contains("AUTOPILOT_REENTRY_COOLDOWN")
    }

    @Test
    fun `(b) 쿨다운 키 없음(윈도우 경과) — Proceed (정당 재진입 허용)`() {
        every { valueOps.get(cooldownKey("006340")) } returns null

        val decision = guard().evaluate(userId, "006340", strategyId = 100L, currentPrice = price)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    @Test
    fun `(c) 다른 code 는 독립 — 한 종목 쿨다운이 다른 종목 막지 않음`() {
        every { valueOps.get(cooldownKey("006340")) } returns recorded(1L, "11070")
        every { valueOps.get(cooldownKey("080220")) } returns null

        val g = guard()
        assertThat(g.evaluate(userId, "006340", 100L, price))
            .isInstanceOf(AutopilotReentryCooldownGuard.Decision.Suppress::class.java)
        assertThat(g.evaluate(userId, "080220", 101L, price))
            .isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    @Test
    fun `(d) enabled=false + pct=0 — Proceed + Redis 조회 0 (zero-regression)`() {
        val decision = guard(enabled = false).evaluate(userId, "006340", strategyId = 100L, currentPrice = price)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
        verify(exactly = 0) { valueOps.get(any()) }
    }

    @Test
    fun `(e) 윈도우 0 이하 + pct=0 — Proceed + Redis 조회 0 (전 게이트 비활성)`() {
        val decision = guard(cooldownMinutes = 0L).evaluate(userId, "006340", strategyId = 100L, currentPrice = price)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
        verify(exactly = 0) { valueOps.get(any()) }
    }

    @Test
    fun `(f) Redis 장애 — fail-open Proceed (정상 신호 막지 않음)`() {
        every { valueOps.get(cooldownKey("006340")) } throws RedisConnectionFailureException("redis down")

        val decision = guard().evaluate(userId, "006340", strategyId = 100L, currentPrice = price)

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    // ── evaluate 가격 축 (시간 축 비활성 cooldownMinutes=0 + pct=1.0) ─────────────────────────────────────

    @Test
    fun `(m) 직전 청산가 +11퍼센트 추격 — 가격 축 Suppress (pct=1_0)`() {
        every { valueOps.get(cooldownKey("006340")) } returns recorded(1L, "11070")

        // 시간 축 비활성(cooldownMinutes=0) → 가격 축만 평가. 현재가 12,340 > 11,070 × 1.01 = 11,180.7 → 추격.
        val decision =
            guard(cooldownMinutes = 0L, priceGuardPct = BigDecimal("1.0"))
                .evaluate(userId, "006340", strategyId = 100L, currentPrice = BigDecimal("12340"))

        assertThat(decision).isInstanceOf(AutopilotReentryCooldownGuard.Decision.Suppress::class.java)
        assertThat((decision as AutopilotReentryCooldownGuard.Decision.Suppress).reason)
            .contains("AUTOPILOT_REENTRY_PRICE_GUARD")
    }

    @Test
    fun `(n) 직전 청산가보다 더 쌈 — 가격 축 Proceed (더 싸게 재진입 허용)`() {
        every { valueOps.get(cooldownKey("006340")) } returns recorded(1L, "11070")

        val decision =
            guard(cooldownMinutes = 0L, priceGuardPct = BigDecimal("1.0"))
                .evaluate(userId, "006340", strategyId = 100L, currentPrice = BigDecimal("11000"))

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    @Test
    fun `(o) pct=0 — 가격 축 비활성 Proceed (롤백 경로)`() {
        every { valueOps.get(cooldownKey("006340")) } returns recorded(1L, "11070")

        val decision =
            guard(cooldownMinutes = 0L, priceGuardPct = BigDecimal.ZERO)
                .evaluate(userId, "006340", strategyId = 100L, currentPrice = BigDecimal("99999"))

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    @Test
    fun `(p) legacy ts-only 값(price 토큰 없음) — 가격 축 fail-open Proceed`() {
        // legacy 포맷(ts only, no ':price') → 가격 파싱 불가 → 가격 축 무효(Proceed).
        every { valueOps.get(cooldownKey("006340")) } returns "1700000000000"

        val decision =
            guard(cooldownMinutes = 0L, priceGuardPct = BigDecimal("1.0"))
                .evaluate(userId, "006340", strategyId = 100L, currentPrice = BigDecimal("99999"))

        assertThat(decision).isEqualTo(AutopilotReentryCooldownGuard.Decision.Proceed)
    }

    // ── recordLiquidation ─────────────────────────────────────────────────────────────────────────────

    @Test
    fun `(g) owner=AUTOPILOT 전량 청산 — 쿨다운 키 ts_price 기록(TTL=윈도우)`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } returns 1L

        guard().recordLiquidation(userId, "006340", strategyId = 100L, exitPrice = BigDecimal("11070"))

        // 값은 `ts:price` 포맷 — price 토큰 11070 이 포함돼야 가격 축이 작동한다.
        verify(exactly = 1) { valueOps.set(cooldownKey("006340"), match { it.endsWith(":11070") }, window15) }
    }

    @Test
    fun `(h) owner≠AUTOPILOT(USER) — 기록 0 (격리 LOCK, USER 무영향)`() {
        every { strategyRepo.countAutopilotStrategyById(200L) } returns 0L

        guard().recordLiquidation(userId, "006340", strategyId = 200L, exitPrice = BigDecimal("11070"))

        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(i) enabled=false — 기록 0 + owner 조회 0 (zero-regression)`() {
        guard(enabled = false).recordLiquidation(userId, "006340", strategyId = 100L, exitPrice = BigDecimal("11070"))

        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(j) strategyId=0(MANUAL sentinel) — 기록 0`() {
        guard().recordLiquidation(userId, "006340", strategyId = 0L, exitPrice = BigDecimal("11070"))

        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }

    @Test
    fun `(k) Redis set 장애 — graceful (체결 권위 무손상, 예외 전파 0)`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } returns 1L
        every { valueOps.set(cooldownKey("006340"), any<String>(), window15) } throws
            RedisConnectionFailureException("redis down")

        guard().recordLiquidation(userId, "006340", strategyId = 100L, exitPrice = BigDecimal("11070"))
    }

    @Test
    fun `(l) owner 조회 실패 — 기록 0 (graceful, USER 오기록 회피)`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } throws RuntimeException("db error")

        guard().recordLiquidation(userId, "006340", strategyId = 100L, exitPrice = BigDecimal("11070"))

        verify(exactly = 0) { valueOps.set(any(), any<String>(), any<Duration>()) }
    }
}

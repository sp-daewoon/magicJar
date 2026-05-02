package com.mj.consumer.application.guard

import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * MarketSessionGuard 단위 테스트. spec §7.1, ADR 0042 @Order(150).
 *
 * 검증 시나리오:
 *   1. Open 상태 → PASS
 *   2. PreMarket 상태 → PASS (KIS에 위임)
 *   3. PostMarket 상태 → PASS (KIS에 위임)
 *   4. Closed + offline-fallback=false → REJECT(SESSION_CLOSED)
 *   5. Closed + offline-fallback=true + dev profile → PASS
 *   6. Closed + offline-fallback=true + prd profile → REJECT (prd는 fallback 무시)
 *   7. Holiday + offline-fallback=false → REJECT(NOT_BUSINESS_DAY)
 *   8. Holiday + offline-fallback=true + dev profile → PASS
 */
class MarketSessionGuardTest {
    private val resolver = mockk<MarketSessionResolver>()
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val account = "50185338"

    private fun session(state: MarketSessionState): MarketSession =
        MarketSession(
            state = state,
            kstAt = OffsetDateTime.now(),
            lastBusinessDate = LocalDate.now(),
            nextOpenAt = null,
        )

    private fun order() =
        Order(
            id = UUID.randomUUID(),
            code = "005930",
            side = Side.BUY,
            qty = 1,
            price = BigDecimal("70000"),
            type = OrderType.LIMIT,
            status = OrderStatus.PENDING,
            createdAt = Instant.now(),
        )

    private fun guard(
        offlineFallback: Boolean,
        activeProfile: String,
    ) = MarketSessionGuard(resolver, offlineFallback, activeProfile)

    // ─── 1. Open → PASS ───

    @Test
    fun `Open - PASS`() {
        every { resolver.resolve() } returns session(MarketSessionState.Open)
        val g = guard(offlineFallback = false, activeProfile = "dev")
        g.check(userId, account, order()) shouldBe GuardDecision.Pass
    }

    // ─── 2. PreMarket → PASS ───

    @Test
    fun `PreMarket - PASS KIS에 위임`() {
        every { resolver.resolve() } returns session(MarketSessionState.PreMarket)
        val g = guard(offlineFallback = false, activeProfile = "dev")
        g.check(userId, account, order()) shouldBe GuardDecision.Pass
    }

    // ─── 3. PostMarket → PASS ───

    @Test
    fun `PostMarket - PASS KIS에 위임`() {
        every { resolver.resolve() } returns session(MarketSessionState.PostMarket)
        val g = guard(offlineFallback = false, activeProfile = "dev")
        g.check(userId, account, order()) shouldBe GuardDecision.Pass
    }

    // ─── 4. Closed + fallback=false → REJECT ───

    @Test
    fun `Closed off-hours - fallback=false → REJECT SESSION_CLOSED`() {
        every { resolver.resolve() } returns session(MarketSessionState.Closed("off-hours"))
        val g = guard(offlineFallback = false, activeProfile = "dev")
        val d = g.check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        (d as GuardDecision.Reject).reason shouldStartWith "SESSION_CLOSED"
    }

    @Test
    fun `Closed Saturday - fallback=false → REJECT SESSION_CLOSED`() {
        every { resolver.resolve() } returns session(MarketSessionState.Closed("Saturday"))
        val g = guard(offlineFallback = false, activeProfile = "dev")
        val d = g.check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        (d as GuardDecision.Reject).reason shouldStartWith "SESSION_CLOSED"
    }

    // ─── 5. Closed + fallback=true + dev → PASS ───

    @Test
    fun `Closed - fallback=true + dev profile → PASS`() {
        every { resolver.resolve() } returns session(MarketSessionState.Closed("Saturday"))
        val g = guard(offlineFallback = true, activeProfile = "dev")
        g.check(userId, account, order()) shouldBe GuardDecision.Pass
    }

    // ─── 6. Closed + fallback=true + prd → REJECT ───

    @Test
    fun `Closed - fallback=true + prd profile → REJECT prd는 fallback 무시`() {
        every { resolver.resolve() } returns session(MarketSessionState.Closed("Saturday"))
        val g = guard(offlineFallback = true, activeProfile = "prd")
        val d = g.check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        (d as GuardDecision.Reject).reason shouldStartWith "SESSION_CLOSED"
    }

    // ─── 7. Holiday + fallback=false → REJECT ───

    @Test
    fun `Holiday - fallback=false → REJECT NOT_BUSINESS_DAY`() {
        every { resolver.resolve() } returns session(MarketSessionState.Holiday(name = "어린이날", reason = "PUBLIC_HOLIDAY"))
        val g = guard(offlineFallback = false, activeProfile = "dev")
        val d = g.check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        (d as GuardDecision.Reject).reason shouldStartWith "NOT_BUSINESS_DAY"
    }

    // ─── 8. Holiday + fallback=true + dev → PASS ───

    @Test
    fun `Holiday - fallback=true + dev → PASS`() {
        every { resolver.resolve() } returns session(MarketSessionState.Holiday(name = "어린이날", reason = "PUBLIC_HOLIDAY"))
        val g = guard(offlineFallback = true, activeProfile = "dev")
        g.check(userId, account, order()) shouldBe GuardDecision.Pass
    }

    @Test
    fun `Holiday - fallback=true + prd → REJECT`() {
        every { resolver.resolve() } returns session(MarketSessionState.Holiday(name = "어린이날", reason = "PUBLIC_HOLIDAY"))
        val g = guard(offlineFallback = true, activeProfile = "prd")
        val d = g.check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
    }
}

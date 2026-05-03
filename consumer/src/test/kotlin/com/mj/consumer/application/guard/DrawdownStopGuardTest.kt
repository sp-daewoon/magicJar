package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.order.OrderJpaAdapter
import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * DrawdownStopGuard 단위 테스트. ADR 0042.
 *
 * totalCapital = 10,000,000원 / limitPercent = -0.03 (-3%) = 허용 손실 -300,000원.
 */
class DrawdownStopGuardTest {
    private val orderJpa = mockk<OrderJpaAdapter>(relaxed = true)
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val account = "50185338"

    private fun guard(
        limitPercent: Double = -0.03,
        totalCapital: Long = 10_000_000L,
    ) = DrawdownStopGuard(orderJpa, limitPercent, totalCapital)

    private fun anyOrder() =
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

    @Test
    fun `오늘 P&L 0 — 손실 없음 PASS`() {
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal.ZERO
        val result = guard().check(userId, account, anyOrder())
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `오늘 이익 발생 (+) — 항상 PASS`() {
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal("150000")
        val result = guard().check(userId, account, anyOrder())
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `손실이 한도 이내 (예 -200000원 -2%) — PASS`() {
        // -200,000 / 10,000,000 = -0.02 > -0.03 한도
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal("-200000")
        val result = guard().check(userId, account, anyOrder())
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `손실이 정확히 한도 (-300000원 -3%) — PASS (경계값)`() {
        // -300,000 / 10,000,000 = -0.03 = 한도와 동일 → PASS
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal("-300000")
        val result = guard().check(userId, account, anyOrder())
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `손실이 한도 초과 (-301000원 -3_01%) — REJECT`() {
        // -301,000 / 10,000,000 = -0.0301 < -0.03 한도
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal("-301000")
        val result = guard().check(userId, account, anyOrder())
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldContain "Drawdown 한도 초과"
    }

    @Test
    fun `손실이 크게 초과 (-1000000원 -10%) — REJECT`() {
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal("-1000000")
        val result = guard().check(userId, account, anyOrder())
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldContain "-10.00%"
    }

    @Test
    fun `총 자본 0원이면 나눗셈 보호 — 항상 PASS`() {
        val zeroCapitalGuard = guard(totalCapital = 0L)
        val result = zeroCapitalGuard.check(userId, account, anyOrder())
        result shouldBe GuardDecision.Pass
    }
}

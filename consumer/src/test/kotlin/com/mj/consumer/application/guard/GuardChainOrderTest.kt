package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterJpaAdapter
import com.mj.consumer.infrastructure.persistence.guard.KillSwitchJpaAdapter
import com.mj.consumer.infrastructure.persistence.market.CandleJpaRepository
import com.mj.consumer.infrastructure.persistence.order.OrderJpaAdapter
import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * Guard chain 6단 순서·우선순위 통합 테스트. ADR 0042.
 *
 * Guard @Order:
 * 100 KillSwitchGuard → 150 MarketSessionGuard (dev/prd Bean) → 200 DailyOrderLimitGuard
 * → 220 PositionLimitGuard (dev/prd) → 240 DrawdownStopGuard (dev/prd) → 300 PriceSanityGuard
 *
 * 이 테스트는 mock profile 환경 시뮬레이션 (MarketSession·PositionLimit·DrawdownStop 제외).
 * dev/prd 6단 full chain은 GuardChainFullOrderTest에서 검증.
 */
class GuardChainOrderTest {
    private val killSwitch = mockk<KillSwitchJpaAdapter>(relaxed = true)
    private val counter = mockk<DailyOrderCounterJpaAdapter>(relaxed = true)
    private val candles = mockk<CandleJpaRepository>(relaxed = true)
    private val orderJpa = mockk<OrderJpaAdapter>(relaxed = true)
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val account = "50185338"

    /** 4단 chain (mock 환경 유사: MarketSession 제외, PositionLimit·DrawdownStop 포함) */
    private fun chain5() =
        GuardChain(
            listOf(
                KillSwitchGuard(killSwitch), // @Order(100)
                DailyOrderLimitGuard(counter, maxCount = 1000, maxValueKrw = 100_000_000), // @Order(200)
                PositionLimitGuard(orderJpa, limitPercent = 0.10, totalCapitalKrw = 10_000_000L), // @Order(220)
                DrawdownStopGuard(orderJpa, limitPercent = -0.03, totalCapitalKrw = 10_000_000L), // @Order(240)
                PriceSanityGuard(candles), // @Order(300)
            ),
        )

    private fun buyOrder(
        code: String = "005930",
        qty: Int = 1,
        price: BigDecimal? = BigDecimal("70000"),
        type: OrderType = OrderType.LIMIT,
    ) = Order(
        id = UUID.randomUUID(),
        code = code,
        side = Side.BUY,
        qty = qty,
        price = price,
        type = type,
        status = OrderStatus.PENDING,
        createdAt = Instant.now(),
    )

    private fun setupAllPass() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns null
        every { orderJpa.positionValue(userId, any()) } returns BigDecimal.ZERO
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal.ZERO
        every { candles.findByCodeAndIntervalAndOpenTimeBetween(any(), any(), any(), any()) } returns emptyList()
    }

    @Test
    fun `순서 100 KillSwitch ON — 뒤 Guard 호출 없이 즉시 short-circuit REJECT`() {
        every { killSwitch.isActive("GLOBAL") } returns true

        val result = chain5().check(userId, account, buyOrder())
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldBe "KILL_SWITCH_ON"

        // counter, orderJpa 호출 0
        verify(exactly = 0) { counter.current(any(), any(), any()) }
        verify(exactly = 0) { orderJpa.positionValue(any(), any()) }
        verify(exactly = 0) { orderJpa.todayRealizedPnl(any()) }
    }

    @Test
    fun `순서 200 DailyOrderLimit 초과 — PositionLimit·DrawdownStop 호출 없이 REJECT`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns
            com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterEntity(
                id =
                    com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterId(
                        java.time.LocalDate.now(),
                        userId,
                        account,
                    ),
                count = 1000, // 한도 도달
                totalValueKrw = BigDecimal.ZERO,
            )

        val result = chain5().check(userId, account, buyOrder())
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldStartWith "DAILY_LIMIT_COUNT_EXCEEDED"

        // PositionLimit·DrawdownStop 호출 0
        verify(exactly = 0) { orderJpa.positionValue(any(), any()) }
        verify(exactly = 0) { orderJpa.todayRealizedPnl(any()) }
    }

    @Test
    fun `순서 220 PositionLimit 초과 — DrawdownStop·PriceSanity 호출 없이 REJECT`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns null
        // 현재 900,000원 + 신규 140,000원(70000×2) = 1,040,000원 > 1,000,000원 한도
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal("900000")

        val result = chain5().check(userId, account, buyOrder(qty = 2, price = BigDecimal("70000")))
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldStartWith "포지션 한도 초과"

        // DrawdownStop 호출 0
        verify(exactly = 0) { orderJpa.todayRealizedPnl(any()) }
        verify(exactly = 0) { candles.findByCodeAndIntervalAndOpenTimeBetween(any(), any(), any(), any()) }
    }

    @Test
    fun `순서 240 DrawdownStop 초과 — PriceSanity 호출 없이 REJECT`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns null
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal.ZERO
        // -301,000원 손실 = -3.01% > -3% 한도
        every { orderJpa.todayRealizedPnl(userId) } returns BigDecimal("-301000")

        val result = chain5().check(userId, account, buyOrder())
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldStartWith "Drawdown 한도 초과"

        verify(exactly = 0) { candles.findByCodeAndIntervalAndOpenTimeBetween(any(), any(), any(), any()) }
    }

    @Test
    fun `순서 300 PriceSanity 초과 — 모든 앞 Guard 통과 후 마지막에 REJECT`() {
        setupAllPass()
        every {
            candles.findByCodeAndIntervalAndOpenTimeBetween("005930", com.mj.domain.common.Interval.D1, any(), any())
        } returns
            listOf(
                com.mj.consumer.infrastructure.persistence.market.CandleJpa(
                    "005930",
                    com.mj.domain.common.Interval.D1,
                    Instant.parse("2026-04-30T00:00:00Z"),
                    BigDecimal("70000"),
                    BigDecimal("71000"),
                    BigDecimal("69000"),
                    BigDecimal("70000"),
                    1000,
                    100,
                ),
            )
        // 95,000원 → ±30% 범위(49000~91000) 초과
        val result = chain5().check(userId, account, buyOrder(price = BigDecimal("95000")))
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldStartWith "PRICE_OUT_OF_RANGE"
    }

    @Test
    fun `전체 6단(5단) 체인 모두 통과 — PASS`() {
        setupAllPass()
        every {
            candles.findByCodeAndIntervalAndOpenTimeBetween("005930", com.mj.domain.common.Interval.D1, any(), any())
        } returns
            listOf(
                com.mj.consumer.infrastructure.persistence.market.CandleJpa(
                    "005930",
                    com.mj.domain.common.Interval.D1,
                    Instant.parse("2026-04-30T00:00:00Z"),
                    BigDecimal("70000"),
                    BigDecimal("71000"),
                    BigDecimal("69000"),
                    BigDecimal("70000"),
                    1000,
                    100,
                ),
            )
        // 70,500원 — ±30% 이내
        val result = chain5().check(userId, account, buyOrder(price = BigDecimal("70500")))
        result shouldBe GuardDecision.Pass
    }
}

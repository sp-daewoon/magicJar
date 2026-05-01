package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterEntity
import com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterId
import com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterJpaAdapter
import com.mj.consumer.infrastructure.persistence.guard.KillSwitchJpaAdapter
import com.mj.consumer.infrastructure.persistence.market.CandleJpa
import com.mj.consumer.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
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
import java.time.LocalDate
import java.util.UUID

class GuardChainTest {
    private val killSwitch = mockk<KillSwitchJpaAdapter>(relaxed = true)
    private val counter = mockk<DailyOrderCounterJpaAdapter>(relaxed = true)
    private val candles = mockk<CandleJpaRepository>(relaxed = true)
    private val userId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val account = "50185338"

    private fun chain(): GuardChain =
        GuardChain(
            listOf(
                KillSwitchGuard(killSwitch),
                DailyOrderLimitGuard(counter, maxCount = 1000, maxValueKrw = 100_000_000),
                PriceSanityGuard(candles),
            ),
        )

    private fun order(
        type: OrderType = OrderType.LIMIT,
        price: BigDecimal? = BigDecimal("70000"),
        qty: Int = 1,
    ) = Order(
        id = UUID.randomUUID(),
        code = "005930",
        side = Side.BUY,
        qty = qty,
        price = price,
        type = type,
        status = OrderStatus.PENDING,
        createdAt = Instant.now(),
    )

    @Test
    fun `KillSwitch ON rejects all orders`() {
        every { killSwitch.isActive("GLOBAL") } returns true
        val d = chain().check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        d.reason shouldBe "KILL_SWITCH_ON"
    }

    @Test
    fun `DailyOrderLimit count exceeded rejects`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns
            DailyOrderCounterEntity(
                id = DailyOrderCounterId(LocalDate.now(), userId, account),
                count = 1000, // 한도 도달
                totalValueKrw = BigDecimal.ZERO,
            )
        val d = chain().check(userId, account, order())
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        d.reason shouldStartWith "DAILY_LIMIT_COUNT_EXCEEDED"
    }

    @Test
    fun `DailyOrderLimit value KRW exceeded rejects`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns
            DailyOrderCounterEntity(
                id = DailyOrderCounterId(LocalDate.now(), userId, account),
                count = 5,
                totalValueKrw = BigDecimal("99999999"), // 1억 직전
            )
        val d = chain().check(userId, account, order(price = BigDecimal("100000"), qty = 1))
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        d.reason shouldStartWith "DAILY_LIMIT_VALUE_EXCEEDED"
    }

    @Test
    fun `PriceSanity outside +-30 percent rejects LIMIT`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns null
        every {
            candles.findByCodeAndIntervalAndOpenTimeBetween("005930", Interval.D1, any(), any())
        } returns
            listOf(
                CandleJpa(
                    "005930",
                    Interval.D1,
                    Instant.parse("2026-04-30T00:00:00Z"),
                    BigDecimal("70000"),
                    BigDecimal("71000"),
                    BigDecimal("69000"),
                    BigDecimal("70000"),
                    1000,
                    100,
                ),
            )
        // 30% 상한선은 91000. 95000원으로 시도 → reject
        val d = chain().check(userId, account, order(price = BigDecimal("95000")))
        d.shouldBeInstanceOf<GuardDecision.Reject>()
        d.reason shouldStartWith "PRICE_OUT_OF_RANGE"
    }

    @Test
    fun `PriceSanity ignores MARKET order`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns null
        // candles는 호출되지 않아야
        val d = chain().check(userId, account, order(type = OrderType.MARKET, price = null))
        d shouldBe GuardDecision.Pass
        verify(exactly = 0) {
            candles.findByCodeAndIntervalAndOpenTimeBetween(any(), any(), any(), any())
        }
    }

    @Test
    fun `chain accepts when all pass and counter increment is called`() {
        every { killSwitch.isActive("GLOBAL") } returns false
        every { counter.current(any(), userId, account) } returns null
        every {
            candles.findByCodeAndIntervalAndOpenTimeBetween("005930", Interval.D1, any(), any())
        } returns
            listOf(
                CandleJpa(
                    "005930",
                    Interval.D1,
                    Instant.parse("2026-04-30T00:00:00Z"),
                    BigDecimal("70000"),
                    BigDecimal("71000"),
                    BigDecimal("69000"),
                    BigDecimal("70000"),
                    1000,
                    100,
                ),
            )
        val d = chain().check(userId, account, order(price = BigDecimal("70500")))
        d shouldBe GuardDecision.Pass
        verify(exactly = 1) { counter.increment(any(), userId, account, any()) }
    }
}

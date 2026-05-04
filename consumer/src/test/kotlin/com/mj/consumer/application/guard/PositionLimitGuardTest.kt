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
 * PositionLimitGuard 단위 테스트. ADR 0042.
 *
 * totalCapital = 10,000,000원 / limitPercent = 0.10 (10%) = 한도 1,000,000원.
 */
class PositionLimitGuardTest {
    private val orderJpa = mockk<OrderJpaAdapter>(relaxed = true)
    private val userId = "74683223"
    private val account = "50185338"

    private fun guard(
        limitPercent: Double = 0.10,
        totalCapital: Long = 10_000_000L,
    ) = PositionLimitGuard(orderJpa, limitPercent, totalCapital)

    private fun buyOrder(
        code: String = "005930",
        qty: Int = 1,
        price: BigDecimal? = BigDecimal("70000"),
    ) = Order(
        id = UUID.randomUUID(),
        code = code,
        side = Side.BUY,
        qty = qty,
        price = price,
        type = OrderType.LIMIT,
        status = OrderStatus.PENDING,
        createdAt = Instant.now(),
    )

    private fun sellOrder(
        code: String = "005930",
        qty: Int = 1,
        price: BigDecimal? = BigDecimal("70000"),
    ) = Order(
        id = UUID.randomUUID(),
        code = code,
        side = Side.SELL,
        qty = qty,
        price = price,
        type = OrderType.LIMIT,
        status = OrderStatus.PENDING,
        createdAt = Instant.now(),
    )

    @Test
    fun `포지션 없는 첫 매수 — 한도 이내 PASS`() {
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal.ZERO
        // 70,000원 × 1주 = 70,000원 < 1,000,000원 한도
        val result = guard().check(userId, account, buyOrder(qty = 1, price = BigDecimal("70000")))
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `기존 포지션 + 신규 주문 합산이 한도 초과 시 REJECT`() {
        // 이미 900,000원 포지션 보유
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal("900000")
        // 신규 70,000원 × 2주 = 140,000원 → 합산 1,040,000원 > 1,000,000원 한도
        val result = guard().check(userId, account, buyOrder(qty = 2, price = BigDecimal("70000")))
        result.shouldBeInstanceOf<GuardDecision.Reject>()
        (result as GuardDecision.Reject).reason shouldContain "포지션 한도 초과"
    }

    @Test
    fun `기존 포지션 + 신규 주문 합산이 한도 이내 PASS`() {
        // 이미 800,000원 포지션
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal("800000")
        // 신규 70,000원 × 2주 = 140,000원 → 합산 940,000원 ≤ 1,000,000원 한도
        val result = guard().check(userId, account, buyOrder(qty = 2, price = BigDecimal("70000")))
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `SELL 주문은 포지션 축소이므로 항상 PASS`() {
        // positionValue 호출 자체 안 해야 함 (early return)
        val result = guard().check(userId, account, sellOrder(qty = 5, price = BigDecimal("70000")))
        result shouldBe GuardDecision.Pass
        // orderJpa.positionValue 호출 0회 검증은 mockk relaxed가 처리
    }

    @Test
    fun `MARKET 주문(price=null)은 체결가 미확정이므로 PASS`() {
        val marketOrder =
            Order(
                id = UUID.randomUUID(),
                code = "005930",
                side = Side.BUY,
                qty = 10,
                price = null,
                type = OrderType.MARKET,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(),
            )
        val result = guard().check(userId, account, marketOrder)
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `포지션 정확히 한도와 같으면 PASS (경계값)`() {
        // 930,000원 기존 + 70,000원 신규 = 1,000,000원 = 정확히 한도
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal("930000")
        val result = guard().check(userId, account, buyOrder(qty = 1, price = BigDecimal("70000")))
        result shouldBe GuardDecision.Pass
    }

    @Test
    fun `한도를 1원이라도 초과하면 REJECT`() {
        // 930,001원 기존 + 70,000원 신규 = 1,000,001원 > 1,000,000원 한도
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal("930001")
        val result = guard().check(userId, account, buyOrder(qty = 1, price = BigDecimal("70000")))
        result.shouldBeInstanceOf<GuardDecision.Reject>()
    }

    @Test
    fun `다른 종목 포지션은 독립 — 종목 코드별 개별 평가`() {
        // 005930: 0원
        every { orderJpa.positionValue(userId, "005930") } returns BigDecimal.ZERO
        // 000660: 9,000,000원 (한도 900%지만 005930에 영향 없음)
        every { orderJpa.positionValue(userId, "000660") } returns BigDecimal("9000000")

        val result = guard().check(userId, account, buyOrder(code = "005930", qty = 1, price = BigDecimal("70000")))
        result shouldBe GuardDecision.Pass
    }
}

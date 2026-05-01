package com.mj.consumer.application.order

import com.mj.consumer.application.guard.GuardChain
import com.mj.consumer.application.guard.GuardDecision
import com.mj.consumer.infrastructure.kis.KisApiException
import com.mj.consumer.infrastructure.kis.KisErrorCategory
import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.order.KisOrderResult
import com.mj.domain.order.OrderEventType
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import com.mj.domain.user.TradingMode
import com.mj.domain.user.port.KisCredential
import com.mj.domain.user.port.KisCredentialProvider
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class KisOrderSenderTest {
    private val kisPort = mockk<KisOrderPort>()
    private val guard = mockk<GuardChain>()
    private val orders = mockk<OrderRepository>(relaxed = true)
    private val events = mockk<OrderEventRepository>(relaxed = true)
    private val creds = mockk<KisCredentialProvider>()
    private val userId = KisOrderSender.DEFAULT_USER_ID

    private val sender = KisOrderSender(kisPort, guard, orders, events, creds)

    private val cred =
        KisCredential(
            tradingMode = TradingMode.MOCK_KIS,
            accountNo = "50185338",
            accountProductCode = "01",
            appKey = "AK",
            appSecret = "AS",
            htsId = "HTSID",
        )

    private fun order(): Order =
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
    fun `guard reject returns REJECTED with reason`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns
            GuardDecision.Reject("KILL_SWITCH_ON")
        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.REJECTED
        r.rejectReason shouldBe "KILL_SWITCH_ON"
        verify(exactly = 0) { kisPort.placeOrder(any(), any()) }
    }

    @Test
    fun `KIS API exception returns REJECTED with category prefix`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns GuardDecision.Pass
        every { kisPort.placeOrder(userId, any()) } throws
            KisApiException("1", "APBK0067", "호가 단위 오류", 200, KisErrorCategory.INVALID_ORDER)

        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.REJECTED
        r.rejectReason!! shouldContain "KIS_INVALID_ORDER"
        r.rejectReason!! shouldContain "APBK0067"
    }

    @Test
    fun `success path saves order, emits PLACED event, returns PENDING`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns GuardDecision.Pass
        every { kisPort.placeOrder(userId, any()) } returns
            KisOrderResult(
                kisOrderNo = "0000123456",
                krxFwdgOrdOrgno = "06010",
                orderTime = "143025",
            )

        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.PENDING
        r.filledQty shouldBe 0

        verify(exactly = 1) { orders.save(match { it.kisOrderNo == "0000123456" && it.kisMode == "MOCK_KIS" }) }
        verify(exactly = 1) {
            events.insertIdempotent(match { it.eventType == OrderEventType.PLACED })
        }
    }
}

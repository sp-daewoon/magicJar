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
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * KisOrderSender 단위 테스트. spec §7, ADR 0034.
 *
 * V11 변경:
 * - Guard reject → orders(REJECTED) INSERT + order_events(REJECTED) INSERT 검증
 * - KisApiException → orders(REJECTED) INSERT + order_events(REJECTED) INSERT 검증
 * - rejectReason: RejectReason.code 형식 ("KILL_SWITCH", "KIS_API:XXX" 등)
 */
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

    // ─── 기본 Guard reject ───

    @Test
    fun `guard reject returns REJECTED with RejectReason code`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns
            GuardDecision.Reject("KILL_SWITCH_ON")
        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.REJECTED
        // mapGuardReason("KILL_SWITCH_ON") → RejectReason.KillSwitchActive.code = "KILL_SWITCH"
        r.rejectReason shouldBe "KILL_SWITCH"
        verify(exactly = 0) { kisPort.placeOrder(any(), any()) }
    }

    // ─── V11 Guard reject → REJECTED audit trail ───

    @Test
    fun `guard reject - orders REJECTED INSERT 호출됨 (ADR 0034)`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns
            GuardDecision.Reject("KILL_SWITCH_ON")

        sender.send(order())

        // orders.save with REJECTED status and rejectReasonCode
        verify(exactly = 1) {
            orders.save(
                match { rec ->
                    rec.status == OrderStatus.REJECTED &&
                        rec.kisOrderNo == null &&
                        rec.rejectReasonCode == "KILL_SWITCH" &&
                        rec.rejectSource == "GUARD"
                },
            )
        }
    }

    @Test
    fun `guard reject - order_events REJECTED INSERT 호출됨 (ADR 0034)`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns
            GuardDecision.Reject("KILL_SWITCH_ON")

        sender.send(order())

        verify(exactly = 1) {
            events.insertIdempotent(
                match { e ->
                    e.eventType == OrderEventType.REJECTED &&
                        e.kisOrderNo == null
                },
            )
        }
    }

    @Test
    fun `session_closed reject maps to SESSION_CLOSED reason`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns
            GuardDecision.Reject("SESSION_CLOSED:Saturday")

        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.REJECTED
        r.rejectReason shouldBe "SESSION_CLOSED"

        verify(exactly = 1) {
            orders.save(match { it.rejectReasonCode == "SESSION_CLOSED" && it.rejectSource == "GUARD" })
        }
    }

    @Test
    fun `not_business_day reject maps to NOT_BUSINESS_DAY reason`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns
            GuardDecision.Reject("NOT_BUSINESS_DAY:어린이날")

        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.REJECTED
        r.rejectReason shouldBe "NOT_BUSINESS_DAY"
    }

    // ─── KIS API exception ───

    @Test
    fun `KIS API exception returns REJECTED with KIS_API prefix`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns GuardDecision.Pass
        every { kisPort.placeOrder(userId, any()) } throws
            KisApiException("1", "APBK0067", "호가 단위 오류", 200, KisErrorCategory.INVALID_ORDER)

        val r = sender.send(order())
        r.resultStatus shouldBe OrderStatus.REJECTED
        // V11: RejectReason.KisApiError(rtCd=e.msgCd, msg=e.msgRaw).code = "KIS_API:APBK0067"
        r.rejectReason shouldBe "KIS_API:APBK0067"
    }

    // ─── V11 KisApiException → REJECTED audit trail ───

    @Test
    fun `KIS API exception - orders REJECTED INSERT 호출됨 (ADR 0034)`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns GuardDecision.Pass
        every { kisPort.placeOrder(userId, any()) } throws
            KisApiException("1", "EGW00202", "GW 라우팅 오류", 200, KisErrorCategory.UNKNOWN)

        sender.send(order())

        verify(exactly = 1) {
            orders.save(
                match { rec ->
                    rec.status == OrderStatus.REJECTED &&
                        rec.kisOrderNo == null &&
                        rec.rejectReasonCode!! == "KIS_API:EGW00202" &&
                        rec.rejectSource == "KIS_API"
                },
            )
        }
    }

    @Test
    fun `KIS API exception - order_events REJECTED INSERT 호출됨 (ADR 0034)`() {
        every { creds.forUser(userId) } returns cred
        every { guard.check(userId, "50185338", any()) } returns GuardDecision.Pass
        every { kisPort.placeOrder(userId, any()) } throws
            KisApiException("1", "40100000", "영업일 아님", 200, KisErrorCategory.MARKET_CLOSED)

        sender.send(order())

        verify(exactly = 1) {
            events.insertIdempotent(
                match { e ->
                    e.eventType == OrderEventType.REJECTED &&
                        e.kisOrderNo == null &&
                        e.payload != null &&
                        e.payload!!.contains("KIS_API:40100000")
                },
            )
        }
    }

    // ─── 정상 흐름 ───

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

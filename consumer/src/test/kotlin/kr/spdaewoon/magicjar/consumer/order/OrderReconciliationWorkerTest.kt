package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderInquireDailyCcldPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderInquireResult
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * OrderReconciliationWorker 단위 테스트 (ADR 0084 §2-2 P0-2 — PENDING_RECONCILE 분기 매트릭스 verify).
 *
 * ### 4+ 케이스 매트릭스
 *  (a) Found + filledQty == orderQty → PENDING_RECONCILE → FILLED + audit KIS_RECONCILE_RESOLVED
 *  (b) Found + filledQty == 0 → PENDING_RECONCILE → ACK + audit KIS_RECONCILE_RESOLVED
 *  (c) NotFound → PENDING_RECONCILE → REJECTED + audit KIS_RECONCILE_RESOLVED
 *  (d) Failed → 그대로 PENDING_RECONCILE + audit 미발행 (다음 tick 재시도)
 *  (e) elapsed > 15분 → PENDING_RECONCILE → EXPIRED + audit KIS_RECONCILE_TIMEOUT
 *  (f) kisOrderNo 부재 → skip (운영자 검증 위임 — 다음 tick에서 EXPIRED 진입)
 *  (g) PENDING_RECONCILE 0건 → cron skip (early return)
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0084 §2-2).
 */
class OrderReconciliationWorkerTest {
    private val orderRequestRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val orderAuditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val kisInquirePort = mockk<KisOrderInquireDailyCcldPort>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val now: Instant = Instant.parse("2026-05-18T10:00:00Z")
    private val fixedClock: Clock = Clock.fixed(now, ZoneId.of("Asia/Seoul"))

    private val orderRequestProvider =
        mockk<ObjectProvider<OrderRequestRepository>>(relaxed = true).also {
            every { it.getIfAvailable() } returns orderRequestRepo
        }
    private val orderAuditProvider =
        mockk<ObjectProvider<OrderAuditEventRepository>>(relaxed = true).also {
            every { it.getIfAvailable() } returns orderAuditRepo
        }

    private val worker =
        OrderReconciliationWorker(
            orderRequestRepositoryProvider = orderRequestProvider,
            orderAuditEventRepositoryProvider = orderAuditProvider,
            kisOrderInquireDailyCcldPort = kisInquirePort,
            objectMapper = objectMapper,
            clock = fixedClock,
        )

    @Test
    fun `(a) Found filledQty == orderQty → FILLED + audit RESOLVED`() {
        val row = stubPendingRow(id = 100L, kisOrderNo = "0000123456", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns
            OrderInquireResult.Found(
                odno = "0000123456",
                krxFwdgOrdOrgno = "ORG001",
                filledQty = 10,
                orderQty = 10,
                ccldStatus = "체결완료",
            )

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.updateKisAck(100L, "0000123456", "ORG001", now) }
        verify(exactly = 1) { orderRequestRepo.markCompleted(100L, now, OrderStatus.FILLED) }

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { orderAuditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_RECONCILE_RESOLVED)
        assertThat(auditSlot.captured.orderId).isEqualTo(100L)
        assertThat(auditSlot.captured.payloadJson).contains("\"finalStatus\":\"FILLED\"")
    }

    @Test
    fun `(b) Found filledQty == 0 → ACK only (markCompleted 미호출)`() {
        val row = stubPendingRow(id = 101L, kisOrderNo = "0000999888", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns
            OrderInquireResult.Found(
                odno = "0000999888",
                krxFwdgOrdOrgno = "ORG002",
                filledQty = 0,
                orderQty = 10,
                ccldStatus = "미체결",
            )

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.updateKisAck(101L, "0000999888", "ORG002", now) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 1) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(c) NotFound → REJECTED + audit RESOLVED`() {
        val row = stubPendingRow(id = 102L, kisOrderNo = "0000555444", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.NotFound

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.markCompleted(102L, now, OrderStatus.REJECTED) }
        verify(exactly = 0) { orderRequestRepo.updateKisAck(any(), any(), any(), any()) }

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { orderAuditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_RECONCILE_RESOLVED)
        assertThat(auditSlot.captured.payloadJson).contains("\"resolution\":\"NOT_FOUND\"")
        assertThat(auditSlot.captured.payloadJson).contains("\"finalStatus\":\"REJECTED\"")
    }

    @Test
    fun `(d) Failed → status 유지 + audit 미발행 (다음 tick 재시도)`() {
        val row = stubPendingRow(id = 103L, kisOrderNo = "0000777666", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.Failed("HTTP_503")

        worker.reconcileTick()

        verify(exactly = 0) { orderRequestRepo.updateKisAck(any(), any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(e) elapsed greater than 15분 → EXPIRED + audit TIMEOUT`() {
        // requestedAt = now - 16분 → elapsed > EXPIRY_THRESHOLD (15분)
        val row = stubPendingRow(id = 104L, kisOrderNo = "0000333222", elapsedMinutes = 16)
        stubPendingLookup(listOf(row))

        worker.reconcileTick()

        verify(exactly = 1) { orderRequestRepo.markCompleted(104L, now, OrderStatus.EXPIRED) }
        // KIS inquire-daily-ccld는 호출되지 않음 — EXPIRED 분기는 timeout 직진
        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { orderAuditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.KIS_RECONCILE_TIMEOUT)
    }

    @Test
    fun `(f) kisOrderNo 부재 → skip (KIS 호출 0건, status 유지)`() {
        val row = stubPendingRow(id = 105L, kisOrderNo = null, elapsedMinutes = 7)
        stubPendingLookup(listOf(row))

        worker.reconcileTick()

        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.updateKisAck(any(), any(), any(), any()) }
        verify(exactly = 0) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(g) PENDING_RECONCILE 0건 → cron early return (KIS 호출 0건)`() {
        stubPendingLookup(emptyList())

        worker.reconcileTick()

        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRequestRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderAuditRepo.save(any()) }
    }

    @Test
    fun `(h) Repository 미합류 → graceful skip (Phase 2 미완 안전)`() {
        every { orderRequestProvider.getIfAvailable() } returns null

        worker.reconcileTick()

        verify(exactly = 0) { kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `(i) inquireByOrderNo 호출 시 day range = today minus 1 to today (KST)`() {
        val row = stubPendingRow(id = 200L, kisOrderNo = "0000111000", elapsedMinutes = 7)
        stubPendingLookup(listOf(row))
        every {
            kisInquirePort.inquireByOrderNo(any(), any(), any(), any(), any())
        } returns OrderInquireResult.NotFound

        worker.reconcileTick()

        val today = LocalDate.now(ZoneId.of("Asia/Seoul"))
        verify(exactly = 1) {
            kisInquirePort.inquireByOrderNo(
                userId = "user-001",
                mode = TradingMode.MOCK,
                kisOrderNo = "0000111000",
                inquireStartDate = today.minusDays(1),
                inquireEndDate = today,
            )
        }
    }

    private fun stubPendingLookup(rows: List<OrderRequest>) {
        every {
            orderRequestRepo.findByStatusAndRequestedAtBefore(
                status = OrderStatus.PENDING_RECONCILE,
                cutoff = any(),
                limit = any(),
            )
        } returns rows
    }

    private fun stubPendingRow(
        id: Long,
        kisOrderNo: String?,
        elapsedMinutes: Long,
    ) = OrderRequest(
        id = id,
        userId = "user-001",
        signalId = 42L,
        strategyId = 100L,
        code = "005930",
        side = OrderSide.BUY,
        priceType = OrderPriceType.MARKET,
        quantity = 10,
        limitPrice = null,
        status = OrderStatus.PENDING_RECONCILE,
        mode = TradingMode.MOCK,
        kisOrderNo = kisOrderNo,
        kisFwdgOrgno = null,
        idempotencyKey = "42:user-001",
        rejectReason = "KIS_RECONCILE_PENDING:KIS_5XX:503",
        requestedAt = now.minusSeconds(elapsedMinutes * 60),
        acknowledgedAt = null,
        completedAt = null,
    )
}

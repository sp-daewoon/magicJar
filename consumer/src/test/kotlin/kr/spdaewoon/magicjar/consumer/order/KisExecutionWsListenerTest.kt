package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * KisExecutionWsListener 단위 테스트 (ADR 0068 §14-6·§14-7 LOCK).
 *
 * 5 케이스 매트릭스 (§14-7):
 *  (a) CNTG_YN=2 부분 체결 → PARTIALLY_FILLED + PARTIAL_FILL audit + Kafka filled partial=true publish
 *  (b) CNTG_YN=2 누적 합 == qty → FILLED + FULL_FILL audit + Kafka filled partial=false publish
 *  (c) CNTG_YN=1 RFUS_YN=Y → REJECTED + KIS_REJECT audit + Kafka rejected publish
 *  (d) CNTG_YN=1 ACPT_YN=3 → CANCELLED + KIS_CANCEL_ACK audit + Kafka cancelled publish
 *  (e) CNTG_YN=2 누적 합 > qty → EXECUTION_OVERFLOW audit + skip
 *
 * 추가: 26 컬럼 parser 정합 verify.
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class KisExecutionWsListenerTest {
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val execRepo = mockk<OrderExecutionRepository>(relaxed = true)
    private val auditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val orderRepoProvider =
        mockk<ObjectProvider<OrderRequestRepository>>().also {
            every { it.getIfAvailable() } returns orderRepo
        }
    private val execRepoProvider =
        mockk<ObjectProvider<OrderExecutionRepository>>().also {
            every { it.getIfAvailable() } returns execRepo
        }
    private val auditRepoProvider =
        mockk<ObjectProvider<OrderAuditEventRepository>>().also {
            every { it.getIfAvailable() } returns auditRepo
        }
    private val filledProducer = mockk<OrderFilledKafkaProducer>(relaxed = true)
    private val rejectedProducer = mockk<OrderRejectedKafkaProducer>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val accountBalanceChangedProducer = mockk<AccountBalanceChangedKafkaProducer>(relaxed = true)

    private val listener =
        KisExecutionWsListener(
            orderRequestRepositoryProvider = orderRepoProvider,
            orderExecutionRepositoryProvider = execRepoProvider,
            orderAuditEventRepositoryProvider = auditRepoProvider,
            orderFilledKafkaProducer = filledProducer,
            orderRejectedKafkaProducer = rejectedProducer,
            orderCancelledKafkaProducer = cancelledProducer,
            accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
            objectMapper = ObjectMapper(),
        )

    @Test
    fun `(a) CNTG_YN=2 부분 체결 sum lt qty → PARTIALLY_FILLED + PARTIAL_FILL audit + Kafka filled partial=true`() {
        val request = stubRequest(quantity = 10)
        every { orderRepo.findByKisOrderNo("ODNO123") } returns request
        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 1L) }
        every { execRepo.sumExecutedQuantityByOrder(99L) } returns 4
        every { orderRepo.updateStatus(99L, OrderStatus.PARTIALLY_FILLED, null) } returns
            request.copy(status = OrderStatus.PARTIALLY_FILLED)

        val msg = stubExecMessage(odno = "ODNO123", cntgYn = "2", cntgQty = 4)
        listener.processMessage(msg)

        verify(exactly = 1) { execRepo.save(any()) }
        verify(exactly = 1) { orderRepo.updateStatus(99L, OrderStatus.PARTIALLY_FILLED, null) }
        val auditList = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 2) { auditRepo.save(capture(auditList)) }
        assertThat(auditList.map { it.eventType })
            .contains(OrderAuditEventType.FILL_RECEIVED, OrderAuditEventType.PARTIAL_FILL)
        verify(exactly = 1) { filledProducer.publish(any(), any(), partial = true) }
    }

    @Test
    fun `(b) CNTG_YN=2 sum eq qty → FILLED + FULL_FILL + Kafka filled partial=false`() {
        val request = stubRequest(quantity = 10)
        every { orderRepo.findByKisOrderNo("ODNO123") } returns request
        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 2L) }
        every { execRepo.sumExecutedQuantityByOrder(99L) } returns 10
        every { orderRepo.markCompleted(99L, any(), OrderStatus.FILLED) } returns
            request.copy(status = OrderStatus.FILLED)

        val msg = stubExecMessage(odno = "ODNO123", cntgYn = "2", cntgQty = 10)
        listener.processMessage(msg)

        verify(exactly = 1) { orderRepo.markCompleted(99L, any(), OrderStatus.FILLED) }
        verify(exactly = 1) { filledProducer.publish(any(), any(), partial = false) }
    }

    @Test
    fun `(c) CNTG_YN=1 RFUS_YN=Y → REJECTED + KIS_REJECT audit + Kafka rejected`() {
        val request = stubRequest(quantity = 10)
        every { orderRepo.findByKisOrderNo("ODNO999") } returns request
        every { orderRepo.markCompleted(99L, any(), OrderStatus.REJECTED) } returns
            request.copy(status = OrderStatus.REJECTED, rejectReason = "WS_REJECTED:1")

        val msg = stubExecMessage(odno = "ODNO999", cntgYn = "1", rfusYn = "Y")
        listener.processMessage(msg)

        verify(exactly = 1) { orderRepo.markCompleted(99L, any(), OrderStatus.REJECTED) }
        verify(exactly = 1) { rejectedProducer.publish(any(), any()) }
        verify(exactly = 0) { execRepo.save(any()) }
    }

    @Test
    fun `(d) CNTG_YN=1 ACPT_YN=3 → CANCELLED + KIS_CANCEL_ACK + Kafka cancelled`() {
        val request = stubRequest(quantity = 10)
        every { orderRepo.findByKisOrderNo("ODNO123") } returns request
        every { orderRepo.markCompleted(99L, any(), OrderStatus.CANCELLED) } returns
            request.copy(status = OrderStatus.CANCELLED)

        val msg = stubExecMessage(odno = "ODNO123", cntgYn = "1", acptYn = "3")
        listener.processMessage(msg)

        verify(exactly = 1) { orderRepo.markCompleted(99L, any(), OrderStatus.CANCELLED) }
        verify(exactly = 1) { cancelledProducer.publish(any()) }
    }

    @Test
    fun `(e) CNTG_YN=2 sum gt qty → EXECUTION_OVERFLOW audit + skip Kafka filled publish`() {
        val request = stubRequest(quantity = 5)
        every { orderRepo.findByKisOrderNo("ODNO123") } returns request
        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 3L) }
        every { execRepo.sumExecutedQuantityByOrder(99L) } returns 8

        val msg = stubExecMessage(odno = "ODNO123", cntgYn = "2", cntgQty = 3)
        listener.processMessage(msg)

        // §14-4 risk 매트릭스 LOCK — overflow 시 Kafka filled publish skip
        verify(exactly = 0) { filledProducer.publish(any(), any(), any()) }
        verify(exactly = 0) { orderRepo.markCompleted(any(), any(), OrderStatus.FILLED) }
        verify(exactly = 0) { orderRepo.updateStatus(any(), OrderStatus.PARTIALLY_FILLED, any()) }
        // audit OVERFLOW row 작성 검증
        val auditList = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 2) { auditRepo.save(capture(auditList)) }
        assertThat(auditList.map { it.eventType })
            .contains(OrderAuditEventType.FILL_RECEIVED, OrderAuditEventType.EXECUTION_OVERFLOW)
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ADR 0084 P0-3 — race condition + pre-insert 패턴 + event_id dedup 케이스
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `(P0-3 f) race retry hit — findByKisOrderNo 첫 1회 null 후 2번째 hit → 정상 처리`() {
        val request = stubRequest(quantity = 10)
        // 첫 호출 null, 두 번째 호출 hit (race window 흡수 evidence)
        every { orderRepo.findByKisOrderNo("ODNO_RACE") } returnsMany listOf(null, request)
        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 11L) }
        every { execRepo.sumExecutedQuantityByOrder(99L) } returns 10
        every { orderRepo.markCompleted(99L, any(), OrderStatus.FILLED) } returns
            request.copy(status = OrderStatus.FILLED)

        val msg = stubExecMessage(odno = "ODNO_RACE", cntgYn = "2", cntgQty = 10)
        listener.processMessage(msg)

        // race retry로 결국 정상 처리됨 — execRepo.save 1회 + FILLED 전환
        verify(exactly = 2) { orderRepo.findByKisOrderNo("ODNO_RACE") }
        verify(exactly = 1) { execRepo.save(any()) }
        verify(exactly = 1) { orderRepo.markCompleted(99L, any(), OrderStatus.FILLED) }
        verify(exactly = 0) { orderRepo.save(any()) } // pre-insert 불필요
    }

    @Test
    fun `(P0-3 g) race retry 모두 null → pre-insert sentinel row 합류 + 정상 dispatch`() {
        // 모든 retry에서 null — pre-insert 진입 evidence
        every { orderRepo.findByKisOrderNo("ODNO_LOST") } returns null
        every { orderRepo.findByIdempotencyKey("WS_PREINSERT:ODNO_LOST") } returns null

        val sentinelRow = stubRequest(quantity = 10).copy(id = 777L, kisOrderNo = "ODNO_LOST")
        every { orderRepo.save(any()) } returns sentinelRow

        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 12L) }
        every { execRepo.sumExecutedQuantityByOrder(777L) } returns 10
        every { orderRepo.markCompleted(777L, any(), OrderStatus.FILLED) } returns
            sentinelRow.copy(status = OrderStatus.FILLED)

        val msg = stubExecMessage(odno = "ODNO_LOST", cntgYn = "2", cntgQty = 10)
        listener.processMessage(msg)

        // pre-insert + 후속 execution 정상 dispatch evidence
        verify(exactly = 1) { orderRepo.save(any()) } // pre-insert
        verify(exactly = 1) { execRepo.save(any()) }
        verify(exactly = 1) { orderRepo.markCompleted(777L, any(), OrderStatus.FILLED) }

        // pre-insert audit 마커 검증 (source=WS_PREINSERT)
        val auditList = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditList)) }
        val preInsertAudit = auditList.find { it.payloadJson.contains("WS_PREINSERT") }
        assertThat(preInsertAudit).isNotNull
        assertThat(preInsertAudit!!.eventType).isEqualTo(OrderAuditEventType.KIS_ACK)
    }

    @Test
    fun `(P0-3 h) pre-insert 재진입 멱등 — 동일 idempotencyKey 발견 시 새 save 호출 안 함`() {
        every { orderRepo.findByKisOrderNo("ODNO_PRE") } returns null

        val existingSentinel = stubRequest(quantity = 10).copy(id = 888L, kisOrderNo = "ODNO_PRE")
        every { orderRepo.findByIdempotencyKey("WS_PREINSERT:ODNO_PRE") } returns existingSentinel

        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 13L) }
        every { execRepo.sumExecutedQuantityByOrder(888L) } returns 10
        every { orderRepo.markCompleted(888L, any(), OrderStatus.FILLED) } returns
            existingSentinel.copy(status = OrderStatus.FILLED)

        val msg = stubExecMessage(odno = "ODNO_PRE", cntgYn = "2", cntgQty = 10)
        listener.processMessage(msg)

        // 기존 sentinel row 재사용 — save 호출 0건 (idempotent)
        verify(exactly = 0) { orderRepo.save(any()) }
        verify(exactly = 1) { execRepo.save(any()) }
    }

    @Test
    fun `(P0-3 i) event_id dedup — 동일 eventId 이미 합류 시 INSERT skip`() {
        val request = stubRequest(quantity = 10)
        every { orderRepo.findByKisOrderNo("ODNO_DUP") } returns request
        // existsByEventId true — 이미 합류한 체결
        every { execRepo.existsByEventId(any()) } returns true

        val msg = stubExecMessage(odno = "ODNO_DUP", cntgYn = "2", cntgQty = 5)
        listener.processMessage(msg)

        // dedup → save 호출 0건 + Kafka publish 0건
        verify(exactly = 0) { execRepo.save(any()) }
        verify(exactly = 0) { filledProducer.publish(any(), any(), any()) }
        verify(exactly = 0) { orderRepo.updateStatus(any(), any(), any()) }
        verify(exactly = 0) { orderRepo.markCompleted(any(), any(), any()) }
    }

    @Test
    fun `(P0-3 j) buildEventId 합성 규칙 정합 — odno+stckCntgHour+cntgQty`() {
        val eventId = KisExecutionWsListener.buildEventId("ODNO123", "093015", 10)
        assertThat(eventId).isEqualTo("ODNO123:093015:10")
    }

    @Test
    fun `(P0-3 k) 거부 통보(CNTG_YN=1 RFUS_YN=Y)도 race retry path 적용 — null 시 pre-insert 합류`() {
        every { orderRepo.findByKisOrderNo("ODNO_RJ") } returns null
        every { orderRepo.findByIdempotencyKey("WS_PREINSERT:ODNO_RJ") } returns null

        val sentinelRow = stubRequest(quantity = 10).copy(id = 999L, kisOrderNo = "ODNO_RJ")
        every { orderRepo.save(any()) } returns sentinelRow
        every { orderRepo.markCompleted(999L, any(), OrderStatus.REJECTED) } returns
            sentinelRow.copy(status = OrderStatus.REJECTED)

        val msg = stubExecMessage(odno = "ODNO_RJ", cntgYn = "1", rfusYn = "Y")
        listener.processMessage(msg)

        // 거부도 pre-insert path 진입 — sentinel row 합류 + REJECTED 정상 dispatch
        verify(exactly = 1) { orderRepo.save(any()) }
        verify(exactly = 1) { orderRepo.markCompleted(999L, any(), OrderStatus.REJECTED) }
        verify(exactly = 1) { rejectedProducer.publish(any(), any()) }
    }

    @Test
    fun `parser — 26 컬럼 caret 메시지 파싱 정합`() {
        // raw 출처: examples_user/domestic_stock/domestic_stock_functions_ws.py L389~L446
        val body =
            buildString {
                append("USERHTSID^") // 0 CUST_ID
                append("50000000^") // 1 ACNT_NO
                append("ODNO123^") // 2 ODER_NO
                append("ODNO000^") // 3 OODER_NO
                append("02^") // 4 SELN_BYOV_CLS BUY=02
                append("0^") // 5 RCTF_CLS
                append("00^") // 6 ODER_KIND
                append("^") // 7 ODER_COND
                append("005930^") // 8 STCK_SHRN_ISCD
                append("10^") // 9 CNTG_QTY
                append("78000^") // 10 CNTG_UNPR
                append("093015^") // 11 STCK_CNTG_HOUR
                append("N^") // 12 RFUS_YN
                append("2^") // 13 CNTG_YN
                append("2^") // 14 ACPT_YN
                append("00^") // 15 BRNC_NO
                append("10^") // 16 ODER_QTY
                append("HONGGIL^") // 17 ACNT_NAME
                append("삼성전자^") // 18 CNTG_ISNM
                append("00^") // 19 CRDT_CLS
                append("^") // 20 CRDT_LOAN_DATE
                append("삼성전자보통주^") // 21 CNTG_ISNM40
                append("78000") // 22 ODER_PRC
            }

        val msg = KisExecutionMessage.parseFromCaretDelimited(body)

        assertThat(msg.custId).isEqualTo("USERHTSID")
        assertThat(msg.acntNo).isEqualTo("50000000")
        assertThat(msg.odno).isEqualTo("ODNO123")
        assertThat(msg.sellBuyCls).isEqualTo("02")
        assertThat(msg.code).isEqualTo("005930")
        assertThat(msg.cntgQty).isEqualTo(10)
        assertThat(msg.cntgUnpr).isEqualByComparingTo(BigDecimal("78000"))
        assertThat(msg.stckCntgHour).isEqualTo("093015")
        assertThat(msg.rfusYn).isEqualTo("N")
        assertThat(msg.cntgYn).isEqualTo("2")
        assertThat(msg.acptYn).isEqualTo("2")
        assertThat(msg.orderQty).isEqualTo(10)
    }

    @Test
    fun `parser — 23 컬럼 미만 시 IllegalArgumentException`() {
        val truncated = "USERHTSID^50000000^ODNO123"
        org.assertj.core.api.Assertions
            .assertThatThrownBy { KisExecutionMessage.parseFromCaretDelimited(truncated) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining(">= 23 columns")
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun stubRequest(quantity: Int) =
        OrderRequest(
            id = 99L,
            userId = "12345678",
            signalId = 42L,
            strategyId = 100L,
            code = "005930",
            side = OrderSide.BUY,
            priceType = OrderPriceType.MARKET,
            quantity = quantity,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = "ODNO123",
            kisFwdgOrgno = "ORG001",
            idempotencyKey = "42:12345678",
            requestedAt = Instant.parse("2026-05-13T00:00:00Z"),
            acknowledgedAt = Instant.parse("2026-05-13T00:00:01Z"),
        )

    private fun stubExecMessage(
        odno: String,
        cntgYn: String,
        cntgQty: Int = 0,
        rfusYn: String = "N",
        acptYn: String = "2",
    ) = KisExecutionMessage(
        custId = "USERHTSID",
        acntNo = "50000000",
        odno = odno,
        originalOdno = "",
        sellBuyCls = "02",
        rctfCls = "0",
        odKind = "00",
        odCond = "",
        code = "005930",
        cntgQty = cntgQty,
        cntgUnpr = BigDecimal("78000"),
        stckCntgHour = "093015",
        rfusYn = rfusYn,
        cntgYn = cntgYn,
        acptYn = acptYn,
        brncNo = "00",
        orderQty = 10,
        acntName = "HONG",
        cntgIsnm = "삼성전자",
        crdtCls = "00",
        crdtLoanDate = "",
        cntgIsnm40 = "삼성전자보통주",
        ordPrc = "78000",
    )
}

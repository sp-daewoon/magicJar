package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisDailyCcldExecution
import kr.spdaewoon.magicjar.consumer.infrastructure.kis.KisOrderInquireCcldHttpAdapter
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialId
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.consumer.ws.KisWebSocketBridge
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * KisExecutionBackfillService 단위 테스트 — ADR 0084 P0-3.
 *
 * 4 케이스 매트릭스:
 *  (a) backfill 1건 — eventId UNIQUE 통과 + OrderRequest 존재 시 정상 INSERT
 *  (b) eventId 중복 — existsByEventId true 시 skip
 *  (c) OrderRequest 부재 — preInsertOrderRequestForBackfill로 sentinel row 합류
 *  (d) backfill 비활성 toggle — register 시 hook 등록 안 됨
 *
 * + buildBackfillEventId 합성 규칙 정합
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0068 §8 + ADR 0084 §2-8).
 */
class KisExecutionBackfillServiceTest {
    private val kisAdapter = mockk<KisOrderInquireCcldHttpAdapter>(relaxed = true)
    private val credentialRepo = mockk<ConsumerUserKisCredentialJpaRepository>(relaxed = true)
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val execRepo = mockk<OrderExecutionRepository>(relaxed = true)
    private val auditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val orderFilledKafkaProducer = mockk<OrderFilledKafkaProducer>(relaxed = true)
    private val accountBalanceChangedKafkaProducer = mockk<AccountBalanceChangedKafkaProducer>(relaxed = true)
    private val positionUpdater = mockk<ExecutionPositionUpdatePort>(relaxed = true)
    private val webSocketBridge = mockk<KisWebSocketBridge>(relaxed = true)
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
    private val positionUpdaterProvider =
        mockk<ObjectProvider<ExecutionPositionUpdatePort>>().also {
            every { it.getIfAvailable() } returns positionUpdater
        }

    private fun newService(
        backfillEnabled: Boolean = true,
        magicjarMode: String = "REAL",
    ) = KisExecutionBackfillService(
        kisOrderInquireCcldHttpAdapter = kisAdapter,
        credentialRepository = credentialRepo,
        orderRequestRepositoryProvider = orderRepoProvider,
        orderExecutionRepositoryProvider = execRepoProvider,
        orderAuditEventRepositoryProvider = auditRepoProvider,
        orderFilledKafkaProducer = orderFilledKafkaProducer,
        accountBalanceChangedKafkaProducer = accountBalanceChangedKafkaProducer,
        executionPositionUpdatePortProvider = positionUpdaterProvider,
        webSocketBridge = webSocketBridge,
        magicjarMode = magicjarMode,
        backfillEnabled = backfillEnabled,
    )

    @Test
    fun `(a) 정상 backfill 1건 — OrderRequest 존재 + eventId 신규 → INSERT`() {
        val service = newService()
        val cred = stubCred(userId = "USER123", accountType = "REAL")
        every { credentialRepo.findAllByAccountType("REAL") } returns listOf(cred)

        val exec = stubBackfillExec(kisOrderNo = "ODNO_BF1", totCcldQty = 5)
        every { kisAdapter.fetchTodayExecutions("USER123", AccountType.REAL) } returns listOf(exec)

        val existingRequest = stubOrderRequest(id = 50L, kisOrderNo = "ODNO_BF1", userId = "USER123")
        every { orderRepo.findByKisOrderNo("ODNO_BF1") } returns existingRequest
        every { execRepo.existsByEventId(any()) } returns false
        every { execRepo.sumExecutedQuantityByOrder(50L) } returns 0 andThen 5
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 100L) }

        service.runBackfill()

        verify(exactly = 1) { execRepo.save(any()) }
        verify(exactly = 1) { positionUpdater.applyExecution(any(), any()) }
        // pre-insert 진입 안 함 — 기존 row 사용
        verify(exactly = 0) { orderRepo.save(any()) }
    }

    @Test
    fun `(b) eventId 중복 — existsByEventId true 시 INSERT skip`() {
        val service = newService()
        val cred = stubCred(userId = "USER123", accountType = "REAL")
        every { credentialRepo.findAllByAccountType("REAL") } returns listOf(cred)

        val exec = stubBackfillExec(kisOrderNo = "ODNO_DUP")
        every { kisAdapter.fetchTodayExecutions("USER123", AccountType.REAL) } returns listOf(exec)

        every { orderRepo.findByKisOrderNo("ODNO_DUP") } returns
            stubOrderRequest(id = 51L, kisOrderNo = "ODNO_DUP", userId = "USER123")
        every { execRepo.existsByEventId(any()) } returns true // 이미 합류
        every { execRepo.sumExecutedQuantityByOrder(51L) } returns 10

        service.runBackfill()

        // dedup → save 호출 0건
        verify(exactly = 0) { execRepo.save(any()) }
        verify(exactly = 1) { orderRepo.findByKisOrderNo("ODNO_DUP") }
    }

    @Test
    fun `(c) OrderRequest 부재 — preInsert sentinel row 합류 후 INSERT`() {
        val service = newService()
        val cred = stubCred(userId = "USER999", accountType = "REAL")
        every { credentialRepo.findAllByAccountType("REAL") } returns listOf(cred)

        val exec = stubBackfillExec(kisOrderNo = "ODNO_NEW", totCcldQty = 7)
        every { kisAdapter.fetchTodayExecutions("USER999", AccountType.REAL) } returns listOf(exec)

        every { execRepo.existsByEventId(any()) } returns false
        every { orderRepo.findByKisOrderNo("ODNO_NEW") } returns null
        every { orderRepo.findByIdempotencyKey("REST_BACKFILL_PREINSERT:ODNO_NEW") } returns null

        val sentinel = stubOrderRequest(id = 60L, kisOrderNo = "ODNO_NEW", userId = "USER999")
        every { orderRepo.save(any()) } returns sentinel

        every { execRepo.sumExecutedQuantityByOrder(60L) } returns 0 andThen 7
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 101L) }

        service.runBackfill()

        // pre-insert 진입 evidence + 후속 OrderExecution INSERT
        verify(exactly = 1) { orderRepo.save(any()) }
        verify(exactly = 1) { execRepo.save(any()) }
    }

    @Test
    fun `(d) 자격증명 부재 — backfill skip (KIS REST 호출 0건)`() {
        val service = newService()
        every { credentialRepo.findAllByAccountType("REAL") } returns emptyList()

        service.runBackfill()

        verify(exactly = 0) { kisAdapter.fetchTodayExecutions(any(), any()) }
        verify(exactly = 0) { execRepo.save(any()) }
    }

    @Test
    fun `(e) backfill 비활성 toggle — hook register 호출되지 않음`() {
        val service = newService(backfillEnabled = false)
        service.registerOnReconnect()

        // webSocketBridge.onReconnect setter 호출 0건 evidence
        verify(exactly = 0) { webSocketBridge.onReconnect = any() }
    }

    @Test
    fun `(f) buildBackfillEventId 합성 규칙 정합 — BACKFILL prefix + odno + ordTmd`() {
        val eventId = KisExecutionBackfillService.buildBackfillEventId("ODNO123", "093015", 10)
        assertThat(eventId).isEqualTo("BACKFILL:ODNO123:093015:10")
    }

    @Test
    fun `(g) 다중 사용자 backfill — 각 사용자별 KIS REST 호출 발사 + 각각 INSERT`() {
        val service = newService()
        val credA = stubCred(userId = "USERAA", accountType = "REAL")
        val credB = stubCred(userId = "USERBB", accountType = "REAL")
        every { credentialRepo.findAllByAccountType("REAL") } returns listOf(credA, credB)

        every { kisAdapter.fetchTodayExecutions("USERAA", AccountType.REAL) } returns
            listOf(stubBackfillExec(kisOrderNo = "ODNO_A1"))
        every { kisAdapter.fetchTodayExecutions("USERBB", AccountType.REAL) } returns
            listOf(stubBackfillExec(kisOrderNo = "ODNO_B1"), stubBackfillExec(kisOrderNo = "ODNO_B2"))

        every { execRepo.existsByEventId(any()) } returns false
        every { orderRepo.findByKisOrderNo(any()) } answers {
            stubOrderRequest(id = 30L, kisOrderNo = firstArg(), userId = "USER")
        }
        every { execRepo.sumExecutedQuantityByOrder(30L) } returnsMany listOf(0, 10, 0, 10, 0, 10)
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 200L) }

        service.runBackfill()

        // 사용자별 KIS REST 호출 1회씩 = 2회
        verify(exactly = 1) { kisAdapter.fetchTodayExecutions("USERAA", AccountType.REAL) }
        verify(exactly = 1) { kisAdapter.fetchTodayExecutions("USERBB", AccountType.REAL) }
        // INSERT 합계 3건 (A:1 + B:2)
        verify(exactly = 3) { execRepo.save(any()) }
    }

    @Test
    fun `(h) MOCK 모드 — accountType MOCK으로 KIS REST 호출 발사`() {
        val service = newService(magicjarMode = "MOCK")
        val cred = stubCred(userId = "USER_MOCK", accountType = "MOCK")
        every { credentialRepo.findAllByAccountType("MOCK") } returns listOf(cred)
        every { kisAdapter.fetchTodayExecutions("USER_MOCK", AccountType.MOCK) } returns emptyList()

        service.runBackfill()

        verify(exactly = 1) { credentialRepo.findAllByAccountType("MOCK") }
        verify(exactly = 1) { kisAdapter.fetchTodayExecutions("USER_MOCK", AccountType.MOCK) }
        verify(exactly = 0) { credentialRepo.findAllByAccountType("REAL") }
    }

    @Test
    fun `(i) runBackfillSafely — runBackfill 예외 발생해도 propagate 안 함`() {
        val service = newService()
        every { credentialRepo.findAllByAccountType(any()) } throws RuntimeException("DB down")

        // 예외 안 던지면 통과 (try-catch graceful)
        service.runBackfillSafely()

        verify(exactly = 0) { execRepo.save(any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun stubCred(
        userId: String,
        accountType: String,
    ) = ConsumerUserKisCredentialJpaEntity(
        id = ConsumerUserKisCredentialId(userId = userId, accountType = accountType),
        accountNumber = "50000000",
        accountPrdtCd = "01",
        appKeyEnc = ByteArray(8),
        secretKeyEnc = ByteArray(8),
    )

    private fun stubBackfillExec(
        kisOrderNo: String,
        totCcldQty: Int = 10,
    ) = KisDailyCcldExecution(
        kisOrderNo = kisOrderNo,
        code = "005930",
        sellBuyCls = "02",
        ordQty = totCcldQty,
        totCcldQty = totCcldQty,
        avgPrice = BigDecimal("78000"),
        ordDt = "20260518",
        ordTmd = "093015",
        executedAt = Instant.parse("2026-05-18T00:30:15Z"),
    )

    private fun stubOrderRequest(
        id: Long,
        kisOrderNo: String,
        userId: String,
    ) = OrderRequest(
        id = id,
        userId = userId,
        signalId = 42L,
        strategyId = 100L,
        code = "005930",
        side = OrderSide.BUY,
        priceType = OrderPriceType.MARKET,
        quantity = 10,
        limitPrice = null,
        status = OrderStatus.ACK,
        mode = TradingMode.REAL,
        kisOrderNo = kisOrderNo,
        idempotencyKey = "42:$userId",
        requestedAt = Instant.parse("2026-05-18T00:00:00Z"),
        acknowledgedAt = Instant.parse("2026-05-18T00:00:01Z"),
    )
}

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
    private val positionUpdater =
        mockk<kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionPositionUpdatePort>(relaxed = true)
    private val executionPersistenceService = ExecutionPersistenceService(execRepo, positionUpdater)
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
        executionPersistenceService = executionPersistenceService,
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
    fun `(j) backlog 82-1 — 이미 FILLED + 합계일치 주문 backfill skip 재진입 시 재마킹·audit·completed_at 불변`() {
        val service = newService()
        val cred = stubCred(userId = "USER_FILLED", accountType = "REAL")
        every { credentialRepo.findAllByAccountType("REAL") } returns listOf(cred)

        // 09:00 체결 완료된 FILLED 주문 — completed_at은 종결 시점에 1회 마킹 권위
        val filledAt = Instant.parse("2026-06-04T00:00:26Z")
        val exec = stubBackfillExec(kisOrderNo = "ODNO_FILLED", totCcldQty = 10)
        every { kisAdapter.fetchTodayExecutions("USER_FILLED", AccountType.REAL) } returns listOf(exec)

        val filledRequest =
            stubOrderRequest(id = 70L, kisOrderNo = "ODNO_FILLED", userId = "USER_FILLED")
                .copy(status = OrderStatus.FILLED, completedAt = filledAt)
        every { orderRepo.findByKisOrderNo("ODNO_FILLED") } returns filledRequest
        // 매시 정각 재연결 → 동일 cumulative event_id 이미 합류 → ALREADY_SYNCED 경로
        every { execRepo.existsByEventId(any()) } returns true
        every { execRepo.sumExecutedQuantityByOrder(70L) } returns 10

        service.runBackfill()

        // 핵심 단언: 종결 상태 재마킹 차단
        verify(exactly = 0) { orderRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { orderRepo.updateStatus(any(), any(), any()) }
        // FULL_FILL audit 중복 발행 차단
        verify(exactly = 0) {
            auditRepo.save(match<OrderAuditEvent> { it.eventType == OrderAuditEventType.FULL_FILL })
        }
        // 신규 execution 합류 0 (skip 경로)
        verify(exactly = 0) { execRepo.save(any()) }
        // Kafka FILLED 이벤트 재발행 0
        verify(exactly = 0) { orderFilledKafkaProducer.publish(any(), any(), any()) }
    }

    @Test
    fun `(k) backlog 82-1 보존 — PARTIALLY_FILLED 주문에 신규 체결 합류 시 FILLED 전환 정상 작동`() {
        val service = newService()
        val cred = stubCred(userId = "USER_PARTIAL", accountType = "REAL")
        every { credentialRepo.findAllByAccountType("REAL") } returns listOf(cred)

        // WS가 누락한 잔여 체결을 backfill이 보정 — 정상 흐름 보존 단언
        val exec = stubBackfillExec(kisOrderNo = "ODNO_PARTIAL", totCcldQty = 10)
        every { kisAdapter.fetchTodayExecutions("USER_PARTIAL", AccountType.REAL) } returns listOf(exec)

        val partialRequest =
            stubOrderRequest(id = 71L, kisOrderNo = "ODNO_PARTIAL", userId = "USER_PARTIAL")
                .copy(status = OrderStatus.PARTIALLY_FILLED)
        every { orderRepo.findByKisOrderNo("ODNO_PARTIAL") } returns partialRequest
        every { execRepo.existsByEventId(any()) } returns false
        // 현재 6주 반영 → delta 4 신규 INSERT → 합계 10 = quantity
        every { execRepo.sumExecutedQuantityByOrder(71L) } returns 6 andThen 10
        every { execRepo.save(any()) } answers { firstArg<OrderExecution>().copy(id = 300L) }
        every { orderRepo.markCompleted(71L, any(), OrderStatus.FILLED) } returns
            partialRequest.copy(status = OrderStatus.FILLED)

        service.runBackfill()

        // 신규 execution 합류 → FILLED 정상 전환 (재마킹 가드에 막히지 않음)
        verify(exactly = 1) { execRepo.save(any()) }
        verify(exactly = 1) { orderRepo.markCompleted(71L, any(), OrderStatus.FILLED) }
        verify(exactly = 1) {
            auditRepo.save(match<OrderAuditEvent> { it.eventType == OrderAuditEventType.FULL_FILL })
        }
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

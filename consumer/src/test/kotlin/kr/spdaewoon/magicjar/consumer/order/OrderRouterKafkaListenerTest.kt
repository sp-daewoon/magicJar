package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.session.SimpleMarketSessionResolver
import kr.spdaewoon.magicjar.consumer.persistence.KillSwitchJpaRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.KisOrderAck
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEvent
import kr.spdaewoon.magicjar.domain.order.execution.OrderAuditEventType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderAuditEventRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderIdempotencyGuard
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.order.execution.port.TradingModeGate
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.http.HttpStatus
import org.springframework.kafka.support.Acknowledgment
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.nio.charset.StandardCharsets

/**
 * OrderRouterKafkaListener 단위 테스트 (ADR 0068 §14-2·§14-7 LOCK).
 *
 * 6 케이스 매트릭스 (§14-7):
 *  (a) intent 정상 → ACK 흐름 (audit KIS_ACK + Kafka requested publish + updateKisAck)
 *  (b) idempotency duplicate → IDEMPOTENCY_SKIPPED audit + 후속 0
 *  (c) real gate denied → REAL_GATE_DENIED audit + Kafka rejected publish
 *  (d) killswitch denied → KILLSWITCH_DENIED audit + Kafka rejected publish
 *  (e) KIS 5xx 1회 retry then fail → KIS_EXCEPTION audit + Kafka rejected publish
 *  (f) KIS rt_cd != 0 reject → KIS_REJECT audit + Kafka rejected publish
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class OrderRouterKafkaListenerTest {
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val auditRepo = mockk<OrderAuditEventRepository>(relaxed = true)
    private val killSwitchPort = mockk<KillSwitchQueryPort>(relaxed = true)
    private val tradingModeGate = mockk<TradingModeGate>()
    private val idempotencyGuard = mockk<OrderIdempotencyGuard>()
    private val kisOrderPort = mockk<KisOrderPort>()
    private val requestedProducer = mockk<OrderRequestedKafkaProducer>(relaxed = true)
    private val rejectedProducer = mockk<OrderRejectedKafkaProducer>(relaxed = true)
    private val accountBalanceChangedProducer = mockk<AccountBalanceChangedKafkaProducer>(relaxed = true)
    private val executionBackfillService = mockk<KisExecutionBackfillService>(relaxed = true)
    private val killSwitchJpaRepository = mockk<KillSwitchJpaRepository>(relaxed = true)
    private val objectMapper = ObjectMapper()

    private val orderRepoProvider =
        mockk<ObjectProvider<OrderRequestRepository>>().also {
            every { it.getIfAvailable() } returns orderRepo
        }
    private val auditRepoProvider =
        mockk<ObjectProvider<OrderAuditEventRepository>>().also {
            every { it.getIfAvailable() } returns auditRepo
        }
    private val killSwitchProvider =
        mockk<ObjectProvider<KillSwitchQueryPort>>().also {
            every { it.getIfAvailable() } returns killSwitchPort
        }
    private val executionBackfillProvider =
        mockk<ObjectProvider<KisExecutionBackfillService>>().also {
            every { it.getIfAvailable() } returns null
        }
    private val killSwitchJpaRepositoryProvider =
        mockk<ObjectProvider<KillSwitchJpaRepository>>().also {
            every { it.getIfAvailable() } returns killSwitchJpaRepository
        }
    private val marketSessionResolver = SimpleMarketSessionResolver()
    private val businessDayCalendar =
        mockk<BusinessDayCalendar>().also {
            every { it.isBusinessDay(any()) } returns true
        }
    private val businessDayCalendarProvider =
        mockk<ObjectProvider<BusinessDayCalendar>>().also {
            every { it.getIfAvailable() } returns businessDayCalendar
        }

    // 기존 12 step 흐름 테스트는 장운영시간 게이트를 비활성(enabled=false)으로 격리한다.
    // 게이트 자체 동작은 isWithinTradingHours 단위 테스트 + AUTO/MANUAL 분기 테스트로 별도 검증.
    private val listener =
        OrderRouterKafkaListener(
            orderRequestRepositoryProvider = orderRepoProvider,
            orderAuditEventRepositoryProvider = auditRepoProvider,
            killSwitchQueryPortProvider = killSwitchProvider,
            tradingModeGate = tradingModeGate,
            orderIdempotencyGuard = idempotencyGuard,
            kisOrderPort = kisOrderPort,
            orderRequestedKafkaProducer = requestedProducer,
            orderRejectedKafkaProducer = rejectedProducer,
            accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
            executionBackfillServiceProvider = executionBackfillProvider,
            killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
            marketSessionResolver = marketSessionResolver,
            businessDayCalendarProvider = businessDayCalendarProvider,
            objectMapper = objectMapper,
            marketHoursGateEnabled = false,
        )

    @Test
    fun `(a) intent ACK 정상 흐름 — IDEM acquire + MOCK mode + KIS ACK → updateKisAck + KIS_ACK audit`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { orderRepo.save(any()) }
        verify(exactly = 1) { requestedProducer.publish(any()) }
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 1) { orderRepo.updateKisAck(99L, "ODNO123", "ORG001", any()) }
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_ACK)
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(b) idempotency duplicate → IDEMPOTENCY_SKIPPED audit + 후속 0`() {
        every { idempotencyGuard.acquire(any(), any()) } returns false

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = slot<OrderAuditEvent>()
        verify(exactly = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.captured.eventType).isEqualTo(OrderAuditEventType.IDEMPOTENCY_SKIPPED)
        verify(exactly = 0) { tradingModeGate.resolveMode(any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `idempotency Redis 장애 → REJECTED 저장과 rejected 이벤트 발행`() {
        every { idempotencyGuard.acquire(any(), any()) } throws IllegalStateException("ORDER_IDEMPOTENCY_REDIS_DOWN")
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 77L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), "IDEMPOTENCY_REDIS_DOWN") }
        verify(exactly = 0) { tradingModeGate.resolveMode(any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(c) real gate denied → REAL_GATE_DENIED audit + Kafka rejected publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns
            OrderResult.Left(DenyReason.RealGateDenied("MAGICJAR_ALLOW_REAL=false"))
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = slot<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.contains("REAL_GATE_DENIED") }) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(d) killswitch ON → KILLSWITCH_DENIED audit + Kafka rejected publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns true
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KILLSWITCH_DENIED)
        verify(exactly = 1) { rejectedProducer.publish(any(), eq("KILLSWITCH_ON")) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
    }

    @Test
    fun `REAL 주문 전 KIS 체결 sync 실패 시 REQUESTED와 KIS 전송 없이 REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.REAL)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { executionBackfillService.syncUserTodayExecutions("12345678", AccountType.REAL) } returns
            KisExecutionSyncResult.failure(
                userId = "12345678",
                accountType = AccountType.REAL,
                error = "KIS_FETCH_FAILED:timeout",
            )
        val syncProvider =
            mockk<ObjectProvider<KisExecutionBackfillService>>().also {
                every { it.getIfAvailable() } returns executionBackfillService
            }
        val realListener =
            OrderRouterKafkaListener(
                orderRequestRepositoryProvider = orderRepoProvider,
                orderAuditEventRepositoryProvider = auditRepoProvider,
                killSwitchQueryPortProvider = killSwitchProvider,
                tradingModeGate = tradingModeGate,
                orderIdempotencyGuard = idempotencyGuard,
                kisOrderPort = kisOrderPort,
                orderRequestedKafkaProducer = requestedProducer,
                orderRejectedKafkaProducer = rejectedProducer,
                accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                executionBackfillServiceProvider = syncProvider,
                killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                marketSessionResolver = marketSessionResolver,
                businessDayCalendarProvider = businessDayCalendarProvider,
                objectMapper = objectMapper,
                magicjarMode = "REAL",
                marketHoursGateEnabled = false,
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        realListener.onOrderIntent(buildRecord(stubPayload()), ack)

        verify(exactly = 1) { executionBackfillService.syncUserTodayExecutions("12345678", AccountType.REAL) }
        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED && it.mode == TradingMode.REAL }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.contains("KIS_EXECUTION_SYNC_STALE") }) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `quantity 0 주문 의도는 KIS 전송 전에 REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(payloadWithQuantity(0)), ack)

        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), "INVALID_ORDER_QUANTITY:0") }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(e) KIS 5xx 1회 retry 후 fail → PENDING_RECONCILE 분기 (ADR 0084 P0-2)`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        // ADR 0084 P0-2 — 5xx fail은 REJECTED 대신 PENDING_RECONCILE 분기.
        every { orderRepo.updateStatus(99L, OrderStatus.PENDING_RECONCILE, any()) } returns null
        // 5xx 2회 throw — 1회 retry 후도 실패
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } throws
            WebClientResponseException.create(
                HttpStatus.INTERNAL_SERVER_ERROR.value(),
                "ISE",
                org.springframework.http.HttpHeaders.EMPTY,
                ByteArray(0),
                StandardCharsets.UTF_8,
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        // submit 2회 호출 — 1회 + 1 retry
        verify(exactly = 2) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        // ADR 0084 P0-2 — audit는 KIS_RECONCILE_PENDING (REJECTED 확정 전 보류 상태).
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_RECONCILE_PENDING)
        // ADR 0084 P0-2 — order.rejected.v1 Kafka publish 0건 (PENDING_RECONCILE 보류 — REJECTED 확정 전).
        verify(exactly = 0) { rejectedProducer.publish(any(), any()) }
        // PENDING_RECONCILE 상태로 updateStatus 1회 호출 정합.
        verify(exactly = 1) { orderRepo.updateStatus(99L, OrderStatus.PENDING_RECONCILE, any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `ORDER_NOT_SENT KIS 예외는 PENDING_RECONCILE이 아니라 REJECTED로 확정`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { orderRepo.updateStatus(99L, OrderStatus.REJECTED, any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Left(DenyReason.KisException("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT:test"))

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_EXCEPTION)
        verify(exactly = 1) {
            orderRepo.updateStatus(
                99L,
                OrderStatus.REJECTED,
                match { it.contains("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT") },
            )
        }
        verify(exactly = 0) { orderRepo.updateStatus(99L, OrderStatus.PENDING_RECONCILE, any()) }
        verify(exactly = 1) {
            rejectedProducer.publish(any(), match { it.contains("ORDER_NOT_SENT:HASHKEY_RATE_LIMIT_TIMEOUT") })
        }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(g §62) Repository 미합류 (NotWired) → listener 진행 중단 + 후속 publish·KIS submit 0`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false

        // OrderRequestRepository provider가 null 반환 — 미합류 시뮬레이션
        val notWiredOrderRepoProvider =
            mockk<ObjectProvider<OrderRequestRepository>>().also {
                every { it.getIfAvailable() } returns null
            }
        val notWiredListener =
            OrderRouterKafkaListener(
                orderRequestRepositoryProvider = notWiredOrderRepoProvider,
                orderAuditEventRepositoryProvider = auditRepoProvider,
                killSwitchQueryPortProvider = killSwitchProvider,
                tradingModeGate = tradingModeGate,
                orderIdempotencyGuard = idempotencyGuard,
                kisOrderPort = kisOrderPort,
                orderRequestedKafkaProducer = requestedProducer,
                orderRejectedKafkaProducer = rejectedProducer,
                accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                executionBackfillServiceProvider = executionBackfillProvider,
                killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                marketSessionResolver = marketSessionResolver,
                businessDayCalendarProvider = businessDayCalendarProvider,
                objectMapper = objectMapper,
                marketHoursGateEnabled = false,
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        notWiredListener.onOrderIntent(buildRecord(stubPayload()), ack)

        // listener 진행 중단 — KIS submit + Kafka publish 모두 0
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 0) { rejectedProducer.publish(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(h §62) Repository save Exception (SaveFailed) → listener 진행 중단 + NotWired와 구분된 로그 + 후속 0`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        // save 자체가 Exception — FK violation 시뮬레이션
        every { orderRepo.save(any()) } throws
            org.springframework.dao.DataIntegrityViolationException(
                "order_request violates foreign key constraint \"order_request_signal_id_fkey\"",
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        // save 1회 시도 (Exception) — 후속 KIS submit + Kafka publish 모두 0
        verify(exactly = 1) { orderRepo.save(any()) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 0) { requestedProducer.publish(any()) }
        verify(exactly = 0) { rejectedProducer.publish(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(f) KIS rt_cd not 0 → KIS_REJECT audit + Kafka rejected publish`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { orderRepo.updateStatus(99L, OrderStatus.REJECTED, any()) } returns null
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Left(DenyReason.KisRejected(rtCd = "40010000", msg = "주문수량 오류"))

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        assertThat(auditSlot.map { it.eventType }).contains(OrderAuditEventType.KIS_REJECT)
        verify(exactly = 1) {
            rejectedProducer.publish(any(), match { it.startsWith("40010000:") })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // T-MAN Phase 1c — ADR 0082 B-2-extended source 필드 분기 (z1·z2·z3)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `(z1) deserialize source=AUTO 매핑 정합 + step 5 INSERT source=AUTO 영속`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        val savedSlot = slot<OrderRequest>()
        every { orderRepo.save(capture(savedSlot)) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO123", kisFwdgOrgno = "ORG001", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(payloadWithSource("AUTO")), ack)

        // 자동 path 회귀 0 — step 5 INSERT source=AUTO
        assertThat(savedSlot.captured.source).isEqualTo(OrderSource.AUTO)
        // audit payload JSON 마커 정합 — DB 컬럼 + JSON 이중 정합 (KIS_ACK)
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        val ackAudit = auditSlot.firstOrNull { it.eventType == OrderAuditEventType.KIS_ACK }
        assertThat(ackAudit).isNotNull
        assertThat(ackAudit!!.payloadJson).contains("\"source\":\"AUTO\"")
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(z2) deserialize source=MANUAL 매핑 정합 + step 5 INSERT source=MANUAL 영속 + audit JSON 마커`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        val savedSlot = slot<OrderRequest>()
        every { orderRepo.save(capture(savedSlot)) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO456", kisFwdgOrgno = "ORG002", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        listener.onOrderIntent(buildRecord(manualPayload()), ack)

        // 수동 path — step 5 INSERT source=MANUAL 영속
        assertThat(savedSlot.captured.source).isEqualTo(OrderSource.MANUAL)
        // KIS_ACK audit payload JSON 마커에 "source"="MANUAL" — DB 컬럼 + JSON 이중 정합
        val auditSlot = mutableListOf<OrderAuditEvent>()
        verify(atLeast = 1) { auditRepo.save(capture(auditSlot)) }
        val ackAudit = auditSlot.firstOrNull { it.eventType == OrderAuditEventType.KIS_ACK }
        assertThat(ackAudit).isNotNull
        assertThat(ackAudit!!.payloadJson).contains("\"source\":\"MANUAL\"")
        // 자동 path 영향 0 — Kafka publish·KIS submit 정상 흐름
        verify(exactly = 1) { requestedProducer.publish(any()) }
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `(z3) deserialize source 필드 부재 → AUTO default fallback (backward compat 회귀 0)`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        val savedSlot = slot<OrderRequest>()
        every { orderRepo.save(capture(savedSlot)) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO789", kisFwdgOrgno = "ORG003", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        // stubPayload — 기존 형식 (source 필드 없음, T-RISK ADR 0067 publisher 정합)
        listener.onOrderIntent(buildRecord(stubPayload()), ack)

        // backward compat — source 필드 부재 시 AUTO default
        assertThat(savedSlot.captured.source).isEqualTo(OrderSource.AUTO)
        verify(exactly = 1) { ack.acknowledge() }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 장운영시간 게이트 (2026-06-04 dogfooding 결함 fix) — AUTO 차단 / MANUAL 통과 / 토글
    // ─────────────────────────────────────────────────────────────────────────

    /** 게이트 활성 + isWithinTradingHours를 결정론적으로 stub한 spy listener. */
    private fun gatedListener(withinHours: Boolean): OrderRouterKafkaListener {
        val gated =
            io.mockk.spyk(
                OrderRouterKafkaListener(
                    orderRequestRepositoryProvider = orderRepoProvider,
                    orderAuditEventRepositoryProvider = auditRepoProvider,
                    killSwitchQueryPortProvider = killSwitchProvider,
                    tradingModeGate = tradingModeGate,
                    orderIdempotencyGuard = idempotencyGuard,
                    kisOrderPort = kisOrderPort,
                    orderRequestedKafkaProducer = requestedProducer,
                    orderRejectedKafkaProducer = rejectedProducer,
                    accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                    executionBackfillServiceProvider = executionBackfillProvider,
                    killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                    marketSessionResolver = marketSessionResolver,
                    businessDayCalendarProvider = businessDayCalendarProvider,
                    objectMapper = objectMapper,
                    marketHoursGateEnabled = true,
                ),
            )
        every { gated.isWithinTradingHours(any()) } returns withinHours
        return gated
    }

    @Test
    fun `AUTO 주문이 장외 시각이면 KIS submit 전에 MARKET_CLOSED로 REJECTED`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }

        val ack = mockk<Acknowledgment>(relaxed = true)
        gatedListener(withinHours = false).onOrderIntent(buildRecord(payloadWithSource("AUTO")), ack)

        verify(exactly = 1) { orderRepo.save(match { it.status == OrderStatus.REJECTED && it.source == OrderSource.AUTO }) }
        verify(exactly = 1) { rejectedProducer.publish(any(), match { it.startsWith("MARKET_CLOSED") }) }
        verify(exactly = 0) { kisOrderPort.submit(any(), any()) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `MANUAL 주문은 장외 시각이어도 게이트를 통과해 KIS submit까지 진행한다`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO999", kisFwdgOrgno = "ORG009", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        gatedListener(withinHours = false).onOrderIntent(buildRecord(manualPayload()), ack)

        // MANUAL은 게이트 제외 — KIS submit 진행
        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 0) { rejectedProducer.publish(any(), match { it.startsWith("MARKET_CLOSED") }) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `AUTO 주문이 정규장 안이면 게이트를 통과해 KIS submit까지 진행한다`() {
        every { idempotencyGuard.acquire(any(), any()) } returns true
        every { tradingModeGate.resolveMode("12345678") } returns OrderResult.Right(TradingMode.MOCK)
        every { killSwitchPort.isOn("12345678") } returns false
        every { orderRepo.save(any()) } answers { firstArg<OrderRequest>().copy(id = 99L) }
        every { kisOrderPort.submit(any(), TradingMode.MOCK) } returns
            OrderResult.Right(
                KisOrderAck(kisOrderNo = "ODNO888", kisFwdgOrgno = "ORG008", rtCd = "0", msg = "OK"),
            )

        val ack = mockk<Acknowledgment>(relaxed = true)
        gatedListener(withinHours = true).onOrderIntent(buildRecord(payloadWithSource("AUTO")), ack)

        verify(exactly = 1) { kisOrderPort.submit(any(), TradingMode.MOCK) }
        verify(exactly = 0) { rejectedProducer.publish(any(), match { it.startsWith("MARKET_CLOSED") }) }
        verify(exactly = 1) { ack.acknowledge() }
    }

    @Test
    fun `isWithinTradingHours — KST 정규장 10시는 true, 장전 08시30분은 false`() {
        val gated =
            OrderRouterKafkaListener(
                orderRequestRepositoryProvider = orderRepoProvider,
                orderAuditEventRepositoryProvider = auditRepoProvider,
                killSwitchQueryPortProvider = killSwitchProvider,
                tradingModeGate = tradingModeGate,
                orderIdempotencyGuard = idempotencyGuard,
                kisOrderPort = kisOrderPort,
                orderRequestedKafkaProducer = requestedProducer,
                orderRejectedKafkaProducer = rejectedProducer,
                accountBalanceChangedKafkaProducer = accountBalanceChangedProducer,
                executionBackfillServiceProvider = executionBackfillProvider,
                killSwitchJpaRepositoryProvider = killSwitchJpaRepositoryProvider,
                marketSessionResolver = marketSessionResolver,
                businessDayCalendarProvider = businessDayCalendarProvider,
                objectMapper = objectMapper,
            )

        // KST 2026-05-20(수) 10:00 = UTC 2026-05-20T01:00:00Z
        assertThat(gated.isWithinTradingHours(java.time.Instant.parse("2026-05-20T01:00:00Z"))).isTrue()
        // KST 2026-05-20(수) 08:30 = UTC 2026-05-19T23:30:00Z (장전)
        assertThat(gated.isWithinTradingHours(java.time.Instant.parse("2026-05-19T23:30:00Z"))).isFalse()
        // KST 2026-05-23(토) 10:00 = UTC 2026-05-23T01:00:00Z (주말 — resolver Closed)
        assertThat(gated.isWithinTradingHours(java.time.Instant.parse("2026-05-23T01:00:00Z"))).isFalse()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun stubPayload(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "interval" to "M5",
                "ts" to 1747094400L,
                "side" to "BUY",
                "strength" to 80,
                "priceAtSignal" to "78000",
                "riskVerdict" to "ALLOWED",
                "emittedAt" to 1747094405L,
            ),
        )

    /**
     * source 필드 명시 payload (z1 AUTO·z2 MANUAL 검증).
     */
    private fun payloadWithSource(source: String): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "interval" to "M5",
                "ts" to 1747094400L,
                "side" to "BUY",
                "strength" to 80,
                "priceAtSignal" to "78000",
                "riskVerdict" to "ALLOWED",
                "emittedAt" to 1747094405L,
                "source" to source,
            ),
        )

    private fun payloadWithQuantity(quantity: Int): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 42,
                "strategyId" to 100,
                "userId" to "12345678",
                "code" to "005930",
                "side" to "BUY",
                "quantity" to quantity,
                "emittedAt" to 1747094405L,
                "source" to "AUTO",
            ),
        )

    /**
     * MANUAL 주문 payload — ADR 0082 T-MAN B-2-extended (signalId=0L·strategyId=0L sentinel + source=MANUAL).
     */
    private fun manualPayload(): String =
        objectMapper.writeValueAsString(
            mapOf(
                "signalId" to 0,
                "strategyId" to 0,
                "userId" to "12345678",
                "code" to "005930",
                "side" to "BUY",
                "priceType" to "MARKET",
                "quantity" to 1,
                "idempotencyKey" to "manual:12345678:1747094405",
                "emittedAt" to 1747094405L,
                "source" to "MANUAL",
            ),
        )

    private fun buildRecord(value: String) = ConsumerRecord<String, String>("order.intent.v1", 0, 0L, "12345678", value)
}

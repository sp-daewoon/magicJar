package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.order.event.OrderEventOutbox
import kr.spdaewoon.magicjar.domain.order.event.OrderOutboxEventType
import kr.spdaewoon.magicjar.domain.order.event.port.OrderEventOutboxRepository
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.core.KafkaTemplate
import java.math.BigDecimal
import java.time.Instant
import kotlinx.datetime.Instant as KxInstant

/**
 * 4 producer outbox 통합 검증 (ADR 0084 P1-1 + spec §2-5).
 *
 * 검증 매트릭스:
 *   (a) OrderRequestedKafkaProducer → outbox.save(ORDER_REQUESTED, order.requested.v1) + markPublished
 *   (b) OrderRejectedKafkaProducer  → outbox.save(ORDER_REJECTED, order.rejected.v1) + markPublished
 *   (c) OrderFilledKafkaProducer    → outbox.save(ORDER_FILLED, order.filled.v1) + markPublished
 *   (d) OrderIntentKafkaProducer    → outbox.save(ORDER_INTENT, order.intent.v1) + markPublished
 *
 * 모든 producer에서 publish 직전 outbox INSERT + publish 성공 시 markPublished 호출 확인.
 *
 * AI 호출 0 — T-O 트랙 정책.
 */
class OrderKafkaProducerOutboxIntegrationTest {
    @Suppress("UNCHECKED_CAST")
    private val kafkaTemplate = mockk<KafkaTemplate<String, String>>(relaxed = true)
    private val objectMapper = ObjectMapper()
    private val outboxRepository = mockk<OrderEventOutboxRepository>(relaxed = true)
    private val provider = mockk<ObjectProvider<OrderEventOutboxRepository>>()

    init {
        every { provider.getIfAvailable() } returns outboxRepository
    }

    private fun sampleRequest(): OrderRequest =
        OrderRequest(
            id = 99L,
            userId = "U1",
            signalId = 1L,
            strategyId = 1L,
            code = "005930",
            side = OrderSide.BUY,
            priceType = OrderPriceType.LIMIT,
            quantity = 10,
            limitPrice = BigDecimal("70000"),
            status = OrderStatus.REQUESTED,
            mode = TradingMode.MOCK,
            idempotencyKey = "idem-1",
            requestedAt = Instant.now(),
        )

    @Test
    fun `(a) OrderRequestedKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 1001L)
        }

        val producer = OrderRequestedKafkaProducer(kafkaTemplate, objectMapper, provider)
        producer.publish(sampleRequest())

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send("order.requested.v1", "U1", any<String>()) }
        verify { outboxRepository.markPublished(1001L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_REQUESTED)
        assertThat(outboxSlot.captured.topic).isEqualTo("order.requested.v1")
        assertThat(outboxSlot.captured.partitionKey).isEqualTo("U1")
        assertThat(outboxSlot.captured.payloadJson).contains("\"userId\":\"U1\"")
    }

    @Test
    fun `(b) OrderRejectedKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 2002L)
        }

        val producer = OrderRejectedKafkaProducer(kafkaTemplate, objectMapper, provider)
        producer.publish(sampleRequest(), reason = "REAL_GATE_DENIED:test")

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send("order.rejected.v1", "U1", any<String>()) }
        verify { outboxRepository.markPublished(2002L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_REJECTED)
        assertThat(outboxSlot.captured.topic).isEqualTo("order.rejected.v1")
        assertThat(outboxSlot.captured.payloadJson).contains("REAL_GATE_DENIED:test")
    }

    @Test
    fun `(c) OrderFilledKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 3003L)
        }

        val producer = OrderFilledKafkaProducer(kafkaTemplate, objectMapper, provider)
        val execution =
            OrderExecution(
                id = 555L,
                orderId = 99L,
                userId = "U1",
                kisOrderNo = "0000000001",
                executedQuantity = 10,
                executedPrice = BigDecimal("70000"),
                executedAt = Instant.now(),
                eventId = "0000000001:090000:10",
                createdAt = Instant.now(),
            )
        producer.publish(sampleRequest(), execution, partial = false)

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send("order.filled.v1", "U1", any<String>()) }
        verify { outboxRepository.markPublished(3003L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_FILLED)
        assertThat(outboxSlot.captured.topic).isEqualTo("order.filled.v1")
    }

    @Test
    fun `(d) OrderIntentKafkaProducer — outbox save + markPublished`() {
        val outboxSlot = slot<OrderEventOutbox>()
        every { outboxRepository.save(capture(outboxSlot)) } answers {
            firstArg<OrderEventOutbox>().copy(id = 4004L)
        }

        val signal =
            Signal(
                id = 77L,
                strategyId = 1L,
                userId = "U1",
                code = "005930",
                interval = CandleInterval.FIVE_MIN,
                ts = KxInstant.fromEpochSeconds(1_736_640_000L),
                side = SignalSide.BUY,
                strength = 75,
                reason = "test",
                priceAtSignal = BigDecimal("70000"),
            )

        val producer = OrderIntentKafkaProducer(kafkaTemplate, objectMapper, provider)
        producer.publish(signal)

        verify { outboxRepository.save(any()) }
        verify { kafkaTemplate.send("order.intent.v1", "U1", any<String>()) }
        verify { outboxRepository.markPublished(4004L) }

        assertThat(outboxSlot.captured.eventType).isEqualTo(OrderOutboxEventType.ORDER_INTENT)
        assertThat(outboxSlot.captured.topic).isEqualTo("order.intent.v1")
    }

    @Test
    fun `(e) outbox provider 미등록 — direct publish fallback`() {
        val emptyProvider = mockk<ObjectProvider<OrderEventOutboxRepository>>()
        every { emptyProvider.getIfAvailable() } returns null

        val producer = OrderRequestedKafkaProducer(kafkaTemplate, objectMapper, emptyProvider)
        producer.publish(sampleRequest())

        verify(exactly = 0) { outboxRepository.save(any()) }
        verify { kafkaTemplate.send("order.requested.v1", "U1", any<String>()) }
    }
}

package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * [PositionStopLossTickWatcher] 단위 테스트 — ADR 0114 R2-C2b ① tick 손절 안전망.
 *
 * 커버: 인덱스 hit/miss · 손절가 도달/미도달 · 이중 발화 멱등(auto-sl) · 수동 SELL 가드 · ACK 보류 · stop-price 산정.
 */
class PositionStopLossTickWatcherTest {
    private val objectMapper = ObjectMapper()
    private val index = mockk<HoldingPositionIndex>()
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)

    private fun watcher(enabled: Boolean = true) =
        PositionStopLossTickWatcher(
            objectMapper = objectMapper,
            holdingPositionIndex = index,
            orderRequestRepositoryProvider = providerOf(orderRepo),
            kisOrderPort = kisOrderPort,
            orderCancelledKafkaProducer = cancelledProducer,
            orderIntentKafkaProducer = intentProducer,
            enabled = enabled,
        )

    private fun held(
        avgPrice: String = "50000",
        stopLossPct: String? = "3",
        qty: Int = 10,
        strategyId: Long = 100L,
        code: String = "005930",
    ) = HoldingPositionIndex.HeldPosition(
        userId = "user-A",
        strategyId = strategyId,
        code = code,
        quantity = qty,
        avgPrice = BigDecimal(avgPrice),
        stopLossPct = stopLossPct?.let { BigDecimal(it) },
        takeProfitPct = BigDecimal("5"),
        updatedAtEpoch = 1_700_000_000L,
    )

    private fun tickRecord(
        code: String = "005930",
        price: String = "48000",
        stale: Boolean = false,
    ): ConsumerRecord<String, String> {
        val payload = objectMapper.writeValueAsString(mapOf("code" to code, "price" to price, "stale" to stale))
        return ConsumerRecord("market.tick.v1", 0, 0L, code, payload)
    }

    @Test
    fun `인덱스 miss — 평가 0 (DB 조회 0)`() {
        every { index.heldBy("005930") } returns emptyList()
        watcher().onTick(tickRecord())
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 0) { orderRepo.findOpenByUserId(any()) }
    }

    @Test
    fun `손절가 미도달 — 주문 0`() {
        // 평단 50,000 × (1-3%) = 48,500 → tick 49,000 > stop → 미발동.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        watcher().onTick(tickRecord(price = "49000"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `손절가 도달 + open SELL 없음 — MARKET intent (auto-sl 키)`() {
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val keySlot = slot<String>()
        watcher().onTick(tickRecord(price = "48000")) // 48,000 ≤ 48,500 stop

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 10,
                limitPrice = null,
                priceAtSignal = BigDecimal("48000"),
                idempotencyKey = capture(keySlot),
                signalId = any(),
            )
        }
        assertThat(keySlot.captured).startsWith("auto-sl:")
    }

    @Test
    fun `이미 open auto-sl SELL 존재 — 중복 발화 0 (멱등)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(idempotencyKey = "auto-sl:abc"))
        watcher().onTick(tickRecord(price = "48000"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `수동 SELL 존재 — 손절 중단 (취소 0 · 발화 0)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(source = OrderSource.MANUAL))
        watcher().onTick(tickRecord(price = "48000"))
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AUTO 같은 전략 SELL(익절) 존재 + ACK 확정 — 취소 후 손절 발화`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns
            listOf(openSell(idempotencyKey = "auto-tp:xyz", source = OrderSource.AUTO))
        every { kisOrderPort.cancel(any(), any()) } returns OrderResult.Right(Unit)
        watcher().onTick(tickRecord(price = "48000"))
        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 1,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `AUTO 같은 전략 SELL ACK 미확정 — 취소 보류 (손절 발화 0)`() {
        every { index.heldBy("005930") } returns listOf(held())
        every { orderRepo.findOpenByUserId("user-A") } returns
            listOf(openSell(idempotencyKey = "auto-tp:xyz", source = OrderSource.AUTO, ackPresent = false))
        watcher().onTick(tickRecord(price = "48000"))
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stale tick — 평가 0`() {
        watcher().onTick(tickRecord(stale = true))
        verify(exactly = 0) { index.heldBy(any()) }
    }

    @Test
    fun `손절 미설정(stopLossPct null) — 발화 0`() {
        every { index.heldBy("005930") } returns listOf(held(stopLossPct = null))
        watcher().onTick(tickRecord(price = "1"))
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `R3 시나리오8 — watcher는 readiness·market-hours·전략status 게이트와 무관하게 tick만으로 발동`() {
        // tick watcher는 1m 경로의 4중 게이트(readiness·market-hours·status·캔들마감)를 일절 참조하지 않는다.
        // 입력은 index hit + tick 가격뿐 — readiness FAILED 상황(1m 경로 BUY/SELL 억제 중)에도 손절은 발동한다.
        // (1m 경로 readiness 억제는 RealtimeSignalGeneratorTest가 별도 커버 — 본 테스트는 watcher 우회 보장만.)
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        watcher().onTick(tickRecord(price = "48000")) // 48,000 ≤ 48,500 stop → 발동

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                OrderSide.SELL,
                OrderPriceType.MARKET,
                any(),
                null,
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `R3 시나리오1 — 손절 발동은 단일 MARKET SELL 멱등(1m 경로 auto-sl 존재 시 중복 0)`() {
        // 1m 경로가 이미 auto-sl SELL을 걸어둔 상태에서 tick이 손절가에 또 닿아도 tick watcher는 재발행하지 않는다.
        every { index.heldBy("005930") } returns listOf(held(avgPrice = "50000", stopLossPct = "3"))
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openSell(idempotencyKey = "auto-sl:1m-path"))
        watcher().onTick(tickRecord(price = "47000"))
        // 이미 open auto-sl 존재 → 중복 발화 0 (멱등 — 단일 MARKET SELL 보장).
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `stopPriceOf — 평단 50000 3퍼센트 = 48500 (호가단위 정렬)`() {
        // 48,500 → 5,000~19,999 구간 10원 단위 → 48,500 그대로.
        val stop = PositionStopLossTickWatcher.stopPriceOf(BigDecimal("50000"), BigDecimal("3"))
        assertThat(stop).isEqualByComparingTo("48500")
    }

    @Test
    fun `stopPriceOf — 비정상 입력 null`() {
        assertThat(PositionStopLossTickWatcher.stopPriceOf(BigDecimal.ZERO, BigDecimal("3"))).isNull()
        assertThat(PositionStopLossTickWatcher.stopPriceOf(BigDecimal("50000"), BigDecimal.ZERO)).isNull()
    }

    private fun openSell(
        idempotencyKey: String = "auto-tp:xyz",
        source: OrderSource = OrderSource.AUTO,
        ackPresent: Boolean = true,
        strategyId: Long = 100L,
    ): OrderRequest =
        OrderRequest(
            id = 9L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = 10,
            limitPrice = BigDecimal("52000"),
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0001234567" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = idempotencyKey,
            requestedAt = Instant.parse("2026-05-22T01:00:00Z"),
            source = source,
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}

package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.ingestion.HoldingDemandRegistrar
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaEntity
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
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
import java.util.Optional

/**
 * [AutoExitOrderCoordinator] 단위 테스트 — ADR 0114 R2-C2b ② 체결 즉시 TP 선주문 + scale-in 재발행.
 *
 * 커버: BUY 체결 TP 선주문 · scale-in 평단 변동 재발행 · 정합 시 재발행 0(중복 방지) · HOLDING 등록 · SELL 전량 청산 release.
 */
class AutoExitOrderCoordinatorTest {
    private val objectMapper = ObjectMapper()
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>()
    private val positionRepo = mockk<StrategyPositionJpaRepository>()
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val index = mockk<HoldingPositionIndex>(relaxed = true)
    private val registrar = mockk<HoldingDemandRegistrar>(relaxed = true)

    private fun coordinator(tpEnabled: Boolean = true) =
        AutoExitOrderCoordinator(
            objectMapper = objectMapper,
            strategyRepository = strategyRepo,
            strategyPositionRepository = positionRepo,
            orderRequestRepositoryProvider = providerOf(orderRepo),
            kisOrderPort = kisOrderPort,
            orderCancelledKafkaProducer = cancelledProducer,
            orderIntentKafkaProducer = intentProducer,
            holdingPositionIndex = index,
            holdingDemandRegistrar = registrar,
            takeProfitLimitEnabled = tpEnabled,
        )

    private fun strategyEntity(takeProfitPct: String? = "5") =
        ConsumerStrategyJpaEntity(
            id = 100L,
            userId = 1L,
            name = "s",
            nameKo = null,
            dslEntry = "x",
            dslExit = null,
            takeProfitPct = takeProfitPct?.let { BigDecimal(it) },
        )

    private fun position(
        qty: Int = 10,
        avgPrice: String = "50000",
    ) = StrategyPositionJpaEntity(
        userId = "user-A",
        strategyId = 100L,
        code = "005930",
        quantity = qty,
        avgPrice = BigDecimal(avgPrice),
        updatedAt = Instant.parse("2026-05-22T01:00:00Z"),
    )

    private fun filledRecord(
        side: OrderSide = OrderSide.BUY,
        qty: Int = 10,
        price: String = "50000",
        source: OrderSource = OrderSource.AUTO,
    ): ConsumerRecord<String, String> {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "orderId" to 9L,
                    "signalId" to 1L,
                    "strategyId" to 100L,
                    "userId" to "user-A",
                    "code" to "005930",
                    "side" to side.name,
                    "executedQty" to qty,
                    "executedPrice" to price,
                    "executedAt" to 1_700_000_000L,
                    "source" to source.name,
                ),
            )
        return ConsumerRecord("order.filled.v1", 0, 0L, "user-A", payload)
    }

    @Test
    fun `BUY 체결 — HOLDING 등록 + 인덱스 즉시 갱신 + TP LIMIT 선주문(auto-tp)`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position()
        every { orderRepo.findOpenByUserId("user-A") } returns emptyList()
        val keySlot = slot<String>()
        val priceSlot = slot<BigDecimal>()

        coordinator().onOrderFilled(filledRecord())

        verify(exactly = 1) { registrar.releasePendingBuy(9L, "005930") }
        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(exactly = 1) { index.onFillRefresh("005930") }
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = OrderPriceType.LIMIT,
                quantity = 10,
                limitPrice = capture(priceSlot),
                priceAtSignal = BigDecimal("50000"),
                idempotencyKey = capture(keySlot),
                signalId = 1L,
            )
        }
        // 50,000 × 1.05 = 52,500 → 50,000~199,999 구간 100원 단위 정렬.
        assertThat(priceSlot.captured).isEqualByComparingTo("52500")
        assertThat(keySlot.captured).startsWith("auto-tp:")
    }

    @Test
    fun `scale-in 평단 변동 — 기존 auto-tp 취소 후 재발행`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        // 추가 체결로 평단 60,000·수량 20으로 변동.
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 20, avgPrice = "60000")
        every { orderRepo.findOpenByUserId("user-A") } returns
            listOf(openTp(qty = 10, limitPrice = "52500")) // 옛 TP(평단 50,000 기준)
        every { kisOrderPort.cancel(any(), any()) } returns OrderResult.Right(Unit)

        coordinator().onOrderFilled(filledRecord(qty = 10, price = "70000"))

        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 1) { cancelledProducer.publish(any()) }
        // 새 평단 60,000 × 1.05 = 63,000 → 50,000~199,999 100원 단위.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                OrderPriceType.LIMIT,
                20,
                match {
                    it!!.compareTo(BigDecimal("63000")) ==
                        0
                },
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `기존 TP가 이미 정합(동일 qty·price) — 취소·재발행 0`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity())
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 10, avgPrice = "50000")
        every { orderRepo.findOpenByUserId("user-A") } returns listOf(openTp(qty = 10, limitPrice = "52500"))

        coordinator().onOrderFilled(filledRecord())

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `takeProfitPct 미설정 — TP 선주문 0 (HOLDING 등록은 수행)`() {
        every { strategyRepo.findById(100L) } returns Optional.of(strategyEntity(takeProfitPct = null))

        coordinator().onOrderFilled(filledRecord())

        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `take-profit-limit-enabled=false — TP 0 (구독은 유지)`() {
        coordinator(tpEnabled = false).onOrderFilled(filledRecord())
        verify(exactly = 1) { registrar.onBuyFilled("005930") }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `SELL 전량 청산 — HOLDING release`() {
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 0)
        every { index.heldBy("005930") } returns emptyList()

        coordinator().onOrderFilled(filledRecord(side = OrderSide.SELL))

        verify(exactly = 1) { index.onFillRefresh("005930") }
        verify(exactly = 1) { registrar.onFullyExited("005930") }
    }

    @Test
    fun `SELL 부분 청산(잔량 남음) — release 0`() {
        every { positionRepo.findByUserIdAndStrategyIdAndCode("user-A", 100L, "005930") } returns position(qty = 5)

        coordinator().onOrderFilled(filledRecord(side = OrderSide.SELL))

        verify(exactly = 1) { index.onFillRefresh("005930") }
        verify(exactly = 0) { registrar.onFullyExited(any()) }
    }

    @Test
    fun `MANUAL 체결 — 무시(TP·HOLDING 0)`() {
        coordinator().onOrderFilled(filledRecord(source = OrderSource.MANUAL))
        verify(exactly = 0) { registrar.onBuyFilled(any()) }
        verify(
            exactly = 0,
        ) { intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    private fun openTp(
        qty: Int,
        limitPrice: String,
        ackPresent: Boolean = true,
    ): OrderRequest =
        OrderRequest(
            id = 7L,
            userId = "user-A",
            signalId = 0L,
            strategyId = 100L,
            code = "005930",
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = qty,
            limitPrice = BigDecimal(limitPrice),
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0001234567" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = "auto-tp:old",
            requestedAt = Instant.parse("2026-05-22T01:00:00Z"),
            source = OrderSource.AUTO,
        )

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}

package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanTarget
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

/**
 * [PlanExecutor] 단위 테스트 — ADR 0130 L2 R2 (계획 청산 보장 실행).
 *
 * 커버: TP/SL 발화 → lot-scoped MARKET SELL · lot 격리(타 전략 lot 미접근) · 게이트 off no-op · 보유 0 종목 미평가 ·
 * cooldown 중복 발행 차단(DSL 병행 dedup 의 인메모리 축).
 */
class PlanExecutorTest {
    private val objectMapper = ObjectMapper()
    private val index = mockk<HoldingPositionIndex>(relaxed = true)
    private val repo = mockk<StrategyPositionJpaRepository>(relaxed = true)
    private val port = mockk<StrategyPositionPort>(relaxed = true)
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val canceller = RestingSellOrderCanceller(kisOrderPort, cancelledProducer)

    init {
        // open SELL 없음 → canceller=Cleared → canProceed.
        every { orderRepo.findOpenByUserId(any()) } returns emptyList()
    }

    private fun executor(enabled: Boolean = true) =
        PlanExecutor(
            objectMapper = objectMapper,
            holdingPositionIndex = index,
            strategyPositionRepository = repo,
            strategyPositionPortProvider = providerOf(port),
            orderRequestRepositoryProvider = providerOf(orderRepo),
            restingSellOrderCanceller = canceller,
            orderIntentKafkaProducer = intentProducer,
            enabled = enabled,
        )

    private fun planWith(
        target: TradePlanTarget? = null,
        stop: TradePlanStop? = null,
    ) = TradePlan(
        entry = TradePlanEntry(strategyId = 100L, entryPrice = BigDecimal("10000"), bar = "1m"),
        target = target,
        stop = stop,
    )

    private fun lot(
        strategyId: Long = 100L,
        code: String = "005930",
        quantity: Int = 10,
        plan: TradePlan = planWith(stop = TradePlanStop(slPct = BigDecimal("2.0"))),
    ) = PlanExecutor.PlannedLot(
        userId = "user-A",
        strategyId = strategyId,
        code = code,
        quantity = quantity,
        avgPrice = BigDecimal("10000"),
        plan = plan,
        openedAtEpoch = Instant.now().epochSecond,
        updatedAtEpoch = Instant.now().epochSecond,
        interval = CandleInterval.ONE_MIN,
    )

    @Test
    fun `SL 도달 — lot-scoped MARKET SELL 발행 + plan CLOSING write`() {
        val l = lot(plan = planWith(stop = TradePlanStop(slPct = BigDecimal("2.0"))))
        val current =
            kr.spdaewoon.magicjar.domain.position.StrategyPosition(
                id = 1L,
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                quantity = 10,
                avgPrice = BigDecimal("10000"),
                tradePlan = l.plan,
                openedAt =
                    kotlinx.datetime.Clock.System
                        .now(),
                updatedAt =
                    kotlinx.datetime.Clock.System
                        .now(),
            )
        every { port.findByUserStrategyCode("user-A", 100L, "005930") } returns current

        executor().evaluate(l, tickPrice = BigDecimal("9700")) // 평단 -3% → 손절선(-2%) 이하.

        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "user-A",
                strategyId = 100L,
                code = "005930",
                intervalCode = any(),
                side = OrderSide.SELL,
                priceType = any(),
                quantity = 10,
                limitPrice = null,
                priceAtSignal = any(),
                idempotencyKey = match { it.startsWith("auto-lq:") },
                orderTrigger = any(),
                triggerRefId = 100L,
                signalId = any(),
            )
        }
        verify(exactly = 1) { port.upsert(any()) } // plan.status=CLOSING write.
    }

    @Test
    fun `TP 도달 — 발행`() {
        val l = lot(plan = planWith(target = TradePlanTarget(tpPct = BigDecimal("3.0"))))
        executor().evaluate(l, tickPrice = BigDecimal("10500")) // +5% → 익절선(+3%) 이상.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `미충족 — 발행 0`() {
        val l = lot(plan = planWith(stop = TradePlanStop(slPct = BigDecimal("2.0"))))
        executor().evaluate(l, tickPrice = BigDecimal("10000")) // 평단=현재가 → 손절선 위.
        verify(exactly = 0) {
            intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `lot 격리 — onTick 은 plan-index 의 그 종목 lot 만 평가(타 전략 lot 미접근)`() {
        every { index.heldBy("005930") } returns listOf(mockk(relaxed = true)) // 보유 있음(hot-path 통과).
        // plan-index 에 전략 100 lot(SL) 만 등록. 전략 999 lot 은 다른 종목이라 005930 tick 으로 접근 안 됨.
        val ex = executor()
        ex.replaceIndexForTest(mapOf("005930" to listOf(lot(strategyId = 100L, code = "005930"))))

        ex.onTick(tickRecord(code = "005930", price = "9700")) // SL 도달.

        verify(exactly = 1) {
            intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
        // 그 발행은 전략 100 한정.
        verify {
            intentProducer.publishExitIntent(
                any(),
                strategyId = 100L,
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                triggerRefId = 100L,
                any(),
            )
        }
    }

    @Test
    fun `게이트 off — onTick no-op (발행 0)`() {
        every { index.heldBy(any()) } returns listOf(mockk(relaxed = true))
        val ex = executor(enabled = false)
        ex.replaceIndexForTest(mapOf("005930" to listOf(lot())))
        ex.onTick(tickRecord(code = "005930", price = "9700"))
        verify(exactly = 0) {
            intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `보유 0 종목 — heldBy 빈 결과면 plan-index 있어도 미평가 (DB hot-path 게이트)`() {
        every { index.heldBy("005930") } returns emptyList()
        val ex = executor()
        ex.replaceIndexForTest(mapOf("005930" to listOf(lot())))
        ex.onTick(tickRecord(code = "005930", price = "9700"))
        verify(exactly = 0) {
            intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    @Test
    fun `cooldown dedup — 같은 lot 상태 연속 발화는 1회만(DSL 병행 이중 SELL 인메모리 축)`() {
        val l = lot(plan = planWith(stop = TradePlanStop(slPct = BigDecimal("2.0"))))
        val ex = executor()
        ex.evaluate(l, tickPrice = BigDecimal("9700"))
        ex.evaluate(l, tickPrice = BigDecimal("9600")) // 같은 lot 상태(qty·updatedAt 동일) → cooldown skip.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any())
        }
    }

    private fun tickRecord(
        code: String,
        price: String,
        stale: Boolean = false,
    ): ConsumerRecord<String, String> {
        val json = objectMapper.writeValueAsString(mapOf("code" to code, "price" to price, "stale" to stale))
        return ConsumerRecord(KafkaTopics.Market.TICK, 0, 0L, code, json)
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}

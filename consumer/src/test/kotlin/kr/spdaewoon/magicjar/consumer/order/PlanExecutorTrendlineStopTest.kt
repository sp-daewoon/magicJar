package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.strategy.TradePlan
import kr.spdaewoon.magicjar.domain.strategy.TradePlanEntry
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStop
import kr.spdaewoon.magicjar.domain.strategy.TradePlanStopType
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineAnchors
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlinePricePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant as JInstant

/**
 * [PlanExecutor.restampTrendlineStops] 봉 경계 재박제 통합 테스트 — ADR 0140 §3-4 R2b.
 *
 * 커버: 우상향 선 상향 재박제 · 손실구간 하향 추종 · 이익잠금 하향 금지 가드 · 선 부재 유지 · 범위 밖 유지 ·
 * FIXED 손절 lot 무영향(대상 격리) · interval 불일치 skip · 게이트 off no-op.
 */
class PlanExecutorTrendlineStopTest {
    private val objectMapper = ObjectMapper()
    private val index = mockk<HoldingPositionIndex>(relaxed = true)
    private val repo = mockk<StrategyPositionJpaRepository>(relaxed = true)
    private val port = mockk<StrategyPositionPort>(relaxed = true)
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val canceller = RestingSellOrderCanceller(kisOrderPort, cancelledProducer)

    private val code = "005930"
    private val userId = "user-A"
    private val strategyId = 100L
    private val trendlineId = 77L

    // 선: anchor1(epoch 0, 9000) — anchor2(epoch 1000, 9500). 봉 경계 ts=500 → 선형보간 9250.
    private val boundaryTs = Instant.fromEpochSeconds(500)

    private fun anchors(
        p1: String = "9000",
        p2: String = "9500",
        extendRight: Boolean = true,
    ) = TrendlineAnchors(
        anchor1Ts = Instant.fromEpochSeconds(0),
        anchor1Price = BigDecimal(p1),
        anchor2Ts = Instant.fromEpochSeconds(1000),
        anchor2Price = BigDecimal(p2),
        extendLeft = true,
        extendRight = extendRight,
    )

    private fun trendlinePort(anchorsResult: TrendlineAnchors?): TrendlinePricePort =
        object : TrendlinePricePort {
            override fun anchorsOf(id: Long): TrendlineAnchors? = if (id == trendlineId) anchorsResult else null
        }

    private fun executor(
        trendlinePort: TrendlinePricePort?,
        enabled: Boolean = true,
    ) = PlanExecutor(
        objectMapper = objectMapper,
        holdingPositionIndex = index,
        strategyPositionRepository = repo,
        strategyPositionPortProvider = providerOf(port),
        orderRequestRepositoryProvider = providerOf(orderRepo),
        restingSellOrderCanceller = canceller,
        orderIntentKafkaProducer = intentProducer,
        enabled = enabled,
        trendlinePricePortProvider = trendlinePort?.let { providerOf(it) },
    )

    private fun trendlinePlan(
        slPrice: String?,
        entryPrice: String = "10000",
    ) = TradePlan(
        entry = TradePlanEntry(strategyId = strategyId, entryPrice = BigDecimal(entryPrice), bar = "1m"),
        stop =
            TradePlanStop(
                type = TradePlanStopType.TRENDLINE,
                slPrice = slPrice?.let { BigDecimal(it) },
                trendlineId = trendlineId,
            ),
    )

    private fun lot(
        plan: TradePlan,
        interval: CandleInterval = CandleInterval.ONE_MIN,
    ) = PlanExecutor.PlannedLot(
        userId = userId,
        strategyId = strategyId,
        code = code,
        quantity = 10,
        avgPrice = BigDecimal("10000"),
        plan = plan,
        openedAtEpoch = JInstant.now().epochSecond,
        updatedAtEpoch = JInstant.now().epochSecond,
        interval = interval,
    )

    private fun stubCurrent(plan: TradePlan) {
        val current =
            StrategyPosition(
                id = 1L,
                userId = userId,
                strategyId = strategyId,
                code = code,
                quantity = 10,
                avgPrice = BigDecimal("10000"),
                tradePlan = plan,
                openedAt =
                    kotlinx.datetime.Clock.System
                        .now(),
                updatedAt =
                    kotlinx.datetime.Clock.System
                        .now(),
            )
        every { port.findByUserStrategyCode(userId, strategyId, code) } returns current
    }

    @Test
    fun `우상향 선 — 현재 손절 9000 → 봉 경계 선 가격 9250 으로 상향 재박제`() {
        val plan = trendlinePlan(slPrice = "9000")
        stubCurrent(plan)
        val captured = slot<StrategyPosition>()
        every { port.upsert(capture(captured)) } answers { captured.captured }

        val ex = executor(trendlinePort(anchors()))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        verify(exactly = 1) { port.upsert(any()) }
        assertEquals(
            0,
            captured.captured.tradePlan!!
                .stop!!
                .slPrice!!
                .compareTo(BigDecimal("9250")),
        )
    }

    @Test
    fun `손실 구간 하향 — 현재 손절 9400(진입 10000 아래) → 선 9250 으로 하향 추종`() {
        val plan = trendlinePlan(slPrice = "9400")
        stubCurrent(plan)
        val captured = slot<StrategyPosition>()
        every { port.upsert(capture(captured)) } answers { captured.captured }

        val ex = executor(trendlinePort(anchors()))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        assertEquals(
            0,
            captured.captured.tradePlan!!
                .stop!!
                .slPrice!!
                .compareTo(BigDecimal("9250")),
        )
    }

    @Test
    fun `이익 잠금 하향 금지 — 현재 손절 10200(진입 이상) 이면 선 9250 하향 재박제 거부(upsert 0)`() {
        val plan = trendlinePlan(slPrice = "10200")
        stubCurrent(plan)

        val ex = executor(trendlinePort(anchors()))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        verify(exactly = 0) { port.upsert(any()) } // 래칫 가드 — 되돌림 금지.
    }

    @Test
    fun `선 부재 — anchorsOf null 이면 마지막 손절값 유지(upsert 0)`() {
        val plan = trendlinePlan(slPrice = "9000")
        stubCurrent(plan)

        val ex = executor(trendlinePort(null))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        verify(exactly = 0) { port.upsert(any()) }
    }

    @Test
    fun `범위 밖 — extendRight=false 선의 anchor2 이후 봉 경계는 유지(upsert 0)`() {
        val plan = trendlinePlan(slPrice = "9000")
        stubCurrent(plan)

        val ex = executor(trendlinePort(anchors(extendRight = false)))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan))))
        // 봉 경계 ts=2000 > anchor2Ts=1000, extendRight=false → priceAt null → 유지.
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, Instant.fromEpochSeconds(2000))

        verify(exactly = 0) { port.upsert(any()) }
    }

    @Test
    fun `대상 격리 — FIXED 손절 lot 은 재박제 대상 아님(upsert 0)`() {
        val fixedPlan =
            TradePlan(
                entry = TradePlanEntry(strategyId = strategyId, entryPrice = BigDecimal("10000"), bar = "1m"),
                stop = TradePlanStop(type = TradePlanStopType.FIXED, slPct = BigDecimal("2.0"), slPrice = BigDecimal("9800")),
            )

        val ex = executor(trendlinePort(anchors()))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(fixedPlan))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        verify(exactly = 0) { port.upsert(any()) }
    }

    @Test
    fun `interval 불일치 — 5m lot 은 1m 봉 경계로 재박제 안 함(upsert 0)`() {
        val plan = trendlinePlan(slPrice = "9000")
        stubCurrent(plan)

        val ex = executor(trendlinePort(anchors()))
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan, interval = CandleInterval.FIVE_MIN))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        verify(exactly = 0) { port.upsert(any()) }
    }

    @Test
    fun `게이트 off — restamp no-op(upsert 0)`() {
        val plan = trendlinePlan(slPrice = "9000")
        val ex = executor(trendlinePort(anchors()), enabled = false)
        ex.replaceIndexForTest(mapOf(code to listOf(lot(plan))))
        ex.restampTrendlineStops(code, CandleInterval.ONE_MIN, boundaryTs)

        verify(exactly = 0) { port.upsert(any()) }
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }
}

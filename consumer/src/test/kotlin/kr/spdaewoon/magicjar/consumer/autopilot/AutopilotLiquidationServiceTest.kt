package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.order.RestingSellOrderCanceller
import kr.spdaewoon.magicjar.consumer.persistence.AutopilotHeldLotProjection
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderTrigger
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * [AutopilotLiquidationService] 단위 테스트 — spec 2026-06-22 §Task 5.
 *
 * 커버:
 * - 청산 intent 가 repository 가 격리한 owner=AUTOPILOT lot 만 대상(다른 owner lot 은 애초 조회에서 제외 — 격리 LOCK 검증).
 * - 롱온리: SELL 수량 = 보유 quantity(전량)·side=SELL·priceType=MARKET·limitPrice=null·음수/공매도 0.
 * - 보유 0건이면 빈 Outcome(no-op — 발행 0건).
 * - 다중 lot 시 Outcome 집계(종목·intent 수) 정합.
 */
class AutopilotLiquidationServiceTest {
    private val positionRepo = mockk<StrategyPositionJpaRepository>()
    private val intentProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val restingSellCanceller = mockk<RestingSellOrderCanceller>(relaxed = true)
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)

    private fun <T : Any> providerOf(value: T?): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    /**
     * 기본 service — orderRepo 미합류(null) 경로(degraded). resting SELL 선취소를 거치지 않으므로 기존 발행 검증이
     * 영향받지 않는다(zero-regression). 선취소 동작은 [service(canceller)] 로 별도 검증.
     */
    private fun service() =
        AutopilotLiquidationService(
            strategyPositionJpaRepository = positionRepo,
            orderIntentKafkaProducer = intentProducer,
            restingSellOrderCanceller = restingSellCanceller,
            orderRequestRepositoryProvider = providerOf(null),
        )

    /** orderRepo 합류 경로 — resting SELL 선취소 동작 검증용. */
    private fun serviceWithRepo() =
        AutopilotLiquidationService(
            strategyPositionJpaRepository = positionRepo,
            orderIntentKafkaProducer = intentProducer,
            restingSellOrderCanceller = restingSellCanceller,
            orderRequestRepositoryProvider = providerOf(orderRepo),
        )

    private fun lot(
        userId: String = "U1",
        strategyId: Long = 100L,
        code: String,
        quantity: Int,
        avgPrice: BigDecimal = BigDecimal("10000"),
    ): AutopilotHeldLotProjection =
        mockk {
            every { getUserId() } returns userId
            every { getStrategyId() } returns strategyId
            every { getCode() } returns code
            every { getQuantity() } returns quantity
            every { getAvgPrice() } returns avgPrice
        }

    @Test
    fun `보유 0건이면 no-op 빈 Outcome — intent 미발행`() {
        every { positionRepo.findAutopilotHeldPositions(any()) } returns emptyList()

        val outcome = service().liquidateAll(userId = "U1", reason = "ROLLBACK")

        assertThat(outcome.liquidatedCodes).isEmpty()
        assertThat(outcome.totalSellIntents).isEqualTo(0)
        verify(exactly = 0) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `owner=AUTOPILOT lot 만 전량 시장가 SELL 청산 — 롱온리 정합`() {
        // repository 가 owner=AUTOPILOT 으로 이미 격리한 lot 만 반환한다(사용자 수동·후성 lot 은 여기 없음).
        every { positionRepo.findAutopilotHeldPositions("U1") } returns
            listOf(
                lot(code = "005930", quantity = 7, avgPrice = BigDecimal("70000")),
                lot(code = "000660", quantity = 3, avgPrice = BigDecimal("120000"), strategyId = 200L),
            )

        val outcome = service().liquidateAll(userId = "U1", reason = "KILL")

        assertThat(outcome.liquidatedCodes).containsExactlyInAnyOrder("005930", "000660")
        assertThat(outcome.totalSellIntents).isEqualTo(2)

        // 005930 — 전량(7) MARKET SELL·limitPrice null·priceAtSignal=avgPrice·AUTO_EXIT.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "U1",
                strategyId = 100L,
                code = "005930",
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 7,
                limitPrice = null,
                priceAtSignal = BigDecimal("70000"),
                idempotencyKey = match { it.startsWith("auto-lq:") },
                orderTrigger = OrderTrigger.AUTO_EXIT,
                triggerRefId = 100L,
            )
        }
        // 000660 — 전량(3) MARKET SELL.
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "U1",
                strategyId = 200L,
                code = "000660",
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 3,
                limitPrice = null,
                priceAtSignal = BigDecimal("120000"),
                idempotencyKey = match { it.startsWith("auto-lq:") },
                orderTrigger = OrderTrigger.AUTO_EXIT,
                triggerRefId = 200L,
            )
        }
    }

    @Test
    fun `비양수 수량 lot 은 발행 skip — 음수·공매도 0`() {
        // 경합 등으로 quantity<=0 lot 이 섞여 들어와도 SELL 발행 안 함(롱온리 가드).
        every { positionRepo.findAutopilotHeldPositions(null) } returns
            listOf(
                lot(code = "005930", quantity = 5),
                lot(code = "000660", quantity = 0, strategyId = 200L),
                lot(code = "035720", quantity = -2, strategyId = 300L),
            )

        val outcome = service().liquidateAll(userId = null, reason = "ROLLBACK")

        assertThat(outcome.liquidatedCodes).containsExactly("005930")
        assertThat(outcome.totalSellIntents).isEqualTo(1)
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                eq("005930"),
                any(),
                eq(OrderSide.SELL),
                eq(OrderPriceType.MARKET),
                eq(5),
                isNull(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
        // quantity<=0 종목은 한 번도 발행 안 됨.
        verify(exactly = 0) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                eq("000660"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
        verify(exactly = 0) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                eq("035720"),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `userId null 이면 account-wide 청산 쿼리로 위임`() {
        every { positionRepo.findAutopilotHeldPositions(null) } returns
            listOf(lot(userId = "U2", code = "005930", quantity = 4))

        val outcome = service().liquidateAll(userId = null, reason = "ROLLBACK")

        assertThat(outcome.totalSellIntents).isEqualTo(1)
        verify(exactly = 1) { positionRepo.findAutopilotHeldPositions(null) }
    }

    // ── ADR 0129 레버 1 — EOD 청산 (allow_overnight=false 격리 쿼리) ──────────────────────────────────────

    @Test
    fun `liquidateNoOvernightAutopilot 은 no-overnight 격리 쿼리로 위임 — allow_overnight=false lot 만 청산`() {
        // findAutopilotHeldPositionsNoOvernight 가 owner=AUTOPILOT AND allow_overnight=false 로 이미 격리.
        every { positionRepo.findAutopilotHeldPositionsNoOvernight("U1") } returns
            listOf(lot(code = "080220", quantity = 5, avgPrice = BigDecimal("134600")))

        val outcome = service().liquidateNoOvernightAutopilot(userId = "U1", reason = "EOD_NO_OVERNIGHT")

        assertThat(outcome.liquidatedCodes).containsExactly("080220")
        assertThat(outcome.totalSellIntents).isEqualTo(1)
        // no-overnight 전용 쿼리만 호출 — liquidateAll 의 전체 쿼리는 타지 않는다(격리·중복 청산 회피).
        verify(exactly = 1) { positionRepo.findAutopilotHeldPositionsNoOvernight("U1") }
        verify(exactly = 0) { positionRepo.findAutopilotHeldPositions(any()) }
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "U1",
                strategyId = 100L,
                code = "080220",
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 5,
                limitPrice = null,
                priceAtSignal = BigDecimal("134600"),
                idempotencyKey = match { it.startsWith("auto-lq:") },
                orderTrigger = OrderTrigger.AUTO_EXIT,
                triggerRefId = 100L,
            )
        }
    }

    // ── 자본경로 결함 fix — EOD 청산 resting SELL 선취소 (2026-06-24 080220/122640 overnight) ──────────────

    @Test
    fun `EOD 청산은 발행 전 resting auto-TP SELL 을 선취소 — 해당 종목 strategyId 로 cancelRestingSells 호출`() {
        // 122640 1주 보유 + 13:40 걸린 auto-TP LIMIT SELL(미체결) → 수량 잠금. 선취소가 없으면 EOD 시장가 REJECTED.
        every { positionRepo.findAutopilotHeldPositionsNoOvernight("U1") } returns
            listOf(lot(code = "122640", quantity = 1, avgPrice = BigDecimal("4500")))
        // canceller 가 resting SELL 취소 성공 → 청산 진행 가능.
        every {
            restingSellCanceller.cancelRestingSells(orderRepo, "U1", 100L, "122640", any())
        } returns RestingSellOrderCanceller.Result.Cancelled(emptyList())

        val outcome = serviceWithRepo().liquidateNoOvernightAutopilot(userId = "U1", reason = "EOD_NO_OVERNIGHT")

        assertThat(outcome.totalSellIntents).isEqualTo(1)
        // 선취소가 시장가 발행 전에 같은 (userId·strategyId·code) 로 호출됐는가.
        verify(exactly = 1) { restingSellCanceller.cancelRestingSells(orderRepo, "U1", 100L, "122640", any()) }
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                userId = "U1",
                strategyId = 100L,
                code = "122640",
                intervalCode = "1m",
                side = OrderSide.SELL,
                priceType = OrderPriceType.MARKET,
                quantity = 1,
                limitPrice = null,
                priceAtSignal = BigDecimal("4500"),
                idempotencyKey = match { it.startsWith("auto-lq:") },
                orderTrigger = OrderTrigger.AUTO_EXIT,
                triggerRefId = 100L,
            )
        }
    }

    @Test
    fun `선취소 미완료(수동 SELL 침범 등 canProceed false)면 그 lot 청산 skip — 시장가 미발행`() {
        // resting SELL 이 수동/타전략(AbortedForeignSell)이라 취소 불가 → 침범 금지로 해당 lot skip(다음 기회 재시도).
        every { positionRepo.findAutopilotHeldPositionsNoOvernight("U1") } returns
            listOf(lot(code = "122640", quantity = 1, avgPrice = BigDecimal("4500")))
        every {
            restingSellCanceller.cancelRestingSells(orderRepo, "U1", 100L, "122640", any())
        } returns RestingSellOrderCanceller.Result.AbortedForeignSell(mockk(relaxed = true))

        val outcome = serviceWithRepo().liquidateNoOvernightAutopilot(userId = "U1", reason = "EOD_NO_OVERNIGHT")

        assertThat(outcome.totalSellIntents).isEqualTo(0)
        verify(exactly = 0) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `rtCd=7(원주문 KIS 부재)은 canceller 가 Cancelled 로 종결 — 청산 그대로 진행`() {
        // #1113 fast-path: 원주문이 이미 KIS 에서 소멸(rtCd=7)하면 canceller 가 already-cancelled 로 Cancelled 반환.
        every { positionRepo.findAutopilotHeldPositionsNoOvernight(null) } returns
            listOf(lot(code = "080220", quantity = 1, avgPrice = BigDecimal("134600")))
        every {
            restingSellCanceller.cancelRestingSells(orderRepo, "U1", 100L, "080220", any())
        } returns RestingSellOrderCanceller.Result.Cancelled(emptyList())

        val outcome = serviceWithRepo().liquidateNoOvernightAutopilot(userId = null, reason = "EOD_NO_OVERNIGHT")

        assertThat(outcome.totalSellIntents).isEqualTo(1)
        verify(exactly = 1) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                eq("080220"),
                any(),
                eq(OrderSide.SELL),
                eq(OrderPriceType.MARKET),
                eq(1),
                isNull(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `orderRepo 미합류면 선취소 없이 청산 진행(degraded) — 청산 우선 성격`() {
        // EOD/kill 청산은 오버나잇·자본 회수가 목적이라 매도 시그널 경로의 fail-closed 보류와 달리 보류하지 않는다.
        every { positionRepo.findAutopilotHeldPositionsNoOvernight("U1") } returns
            listOf(lot(code = "122640", quantity = 1, avgPrice = BigDecimal("4500")))

        val outcome = service().liquidateNoOvernightAutopilot(userId = "U1", reason = "EOD_NO_OVERNIGHT")

        assertThat(outcome.totalSellIntents).isEqualTo(1)
        verify(exactly = 0) { restingSellCanceller.cancelRestingSells(any(), any(), any(), any(), any()) }
    }

    @Test
    fun `EOD 대상 0건(allow_overnight=false lot 없음)이면 no-op — intent 미발행`() {
        every { positionRepo.findAutopilotHeldPositionsNoOvernight(null) } returns emptyList()

        val outcome = service().liquidateNoOvernightAutopilot(userId = null, reason = "EOD_NO_OVERNIGHT")

        assertThat(outcome.totalSellIntents).isEqualTo(0)
        verify(exactly = 0) {
            intentProducer.publishExitIntent(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
            )
        }
    }
}

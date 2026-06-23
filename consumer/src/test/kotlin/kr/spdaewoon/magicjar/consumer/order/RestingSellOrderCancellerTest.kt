package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.DenyReason
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderResult
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * [RestingSellOrderCanceller] 단위 테스트 — rtCd=7(원주문 KIS 부재) already-cancelled fast-path 중심.
 *
 * ## 라이브 결함 (REAL 2026-06-23 09:03~09:06)
 * 어제 15:15 걸린 익절 지정가 SELL(ACK)이 KIS 일중주문이라 장마감 자동 소멸 → 우리 DB는 ACK(resting) 잔류.
 * 개장 손절 SELL이 선취소 시도 → KIS가 `rtCd=7("원주문정보가 존재하지않습니다")` 반환 → 무한 CancelFailed →
 * reservedSell 미해제 → `SELL_QUANTITY_UNAVAILABLE`로 손절 영구 보류. 본 fast-path가 그 교착을 근본 해소.
 *
 * 커버: rtCd=7 already-cancelled 종결+진행 · 그 외 rtCd CancelFailed 보존 · KisException 보존 ·
 *       수동/타전략 침범 금지 보존 · 일부 rtCd=7+일부 성공 혼합.
 */
class RestingSellOrderCancellerTest {
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val cancelledProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val orderRepo = mockk<OrderRequestRepository>(relaxed = true)

    private val canceller = RestingSellOrderCanceller(kisOrderPort, cancelledProducer)

    private val userId = "user-A"
    private val strategyId = 100L
    private val code = "080220"

    @Test
    fun `rtCd=7(원주문 KIS 부재) — already-cancelled 종결 후 진행(canProceed)`() {
        val resting = openSell(id = 507L)
        every { orderRepo.findOpenByUserId(userId) } returns listOf(resting)
        every { kisOrderPort.cancel(resting, resting.mode) } returns
            OrderResult.Left(DenyReason.KisRejected(rtCd = "7", msg = "원주문정보가 존재하지않습니다."))

        val result = cancelRestingSells()

        // already-cancelled로 간주 → publishCancelled(markCompleted CANCELLED + Kafka publish) 수행.
        verify(exactly = 1) { orderRepo.markCompleted(507L, any(), OrderStatus.CANCELLED) }
        verify(exactly = 1) { cancelledProducer.publish(any()) }
        // 루프 계속 → CancelFailed가 아니라 Cancelled로 종결, 청산 진행 가능.
        assertThat(result).isInstanceOf(RestingSellOrderCanceller.Result.Cancelled::class.java)
        assertThat(result.canProceed).isTrue()
    }

    @Test
    fun `rtCd!=7(예 1) — CancelFailed 보존(기존 동작 유지)`() {
        val resting = openSell(id = 507L)
        every { orderRepo.findOpenByUserId(userId) } returns listOf(resting)
        every { kisOrderPort.cancel(resting, resting.mode) } returns
            OrderResult.Left(DenyReason.KisRejected(rtCd = "1", msg = "기타 거부"))

        val result = cancelRestingSells()

        verify(exactly = 0) { orderRepo.markCompleted(any(), any(), any()) }
        verify(exactly = 0) { cancelledProducer.publish(any()) }
        assertThat(result).isInstanceOf(RestingSellOrderCanceller.Result.CancelFailed::class.java)
        assertThat(result.canProceed).isFalse()
    }

    @Test
    fun `KisException — CancelFailed 보존`() {
        val resting = openSell(id = 507L)
        every { orderRepo.findOpenByUserId(userId) } returns listOf(resting)
        every { kisOrderPort.cancel(resting, resting.mode) } returns
            OrderResult.Left(DenyReason.KisException(cause = "4xx parse 실패"))

        val result = cancelRestingSells()

        verify(exactly = 0) { orderRepo.markCompleted(any(), any(), any()) }
        assertThat(result).isInstanceOf(RestingSellOrderCanceller.Result.CancelFailed::class.java)
        assertThat(result.canProceed).isFalse()
    }

    @Test
    fun `수동 SELL 침범 금지 — AbortedForeignSell(rtCd 무관, 취소 0)`() {
        val foreign = openSell(id = 600L, source = OrderSource.MANUAL)
        every { orderRepo.findOpenByUserId(userId) } returns listOf(foreign)

        val result = cancelRestingSells()

        // 수동 SELL은 cancel 시도조차 하지 않는다(침범 금지 보존).
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 0) { cancelledProducer.publish(any()) }
        assertThat(result).isInstanceOf(RestingSellOrderCanceller.Result.AbortedForeignSell::class.java)
        assertThat(result.canProceed).isFalse()
    }

    @Test
    fun `타전략 SELL 침범 금지 — AbortedForeignSell(취소 0)`() {
        val foreign = openSell(id = 601L, source = OrderSource.AUTO, strategyId = 999L)
        every { orderRepo.findOpenByUserId(userId) } returns listOf(foreign)

        val result = cancelRestingSells()

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        assertThat(result).isInstanceOf(RestingSellOrderCanceller.Result.AbortedForeignSell::class.java)
    }

    @Test
    fun `여러 open SELL 중 일부 rtCd=7 일부 성공 — 전부 처리 후 Cancelled`() {
        val expired = openSell(id = 507L, idempotencyKey = "auto-tp:expired")
        val live = openSell(id = 508L, idempotencyKey = "auto-tp:live")
        every { orderRepo.findOpenByUserId(userId) } returns listOf(expired, live)
        every { kisOrderPort.cancel(expired, expired.mode) } returns
            OrderResult.Left(DenyReason.KisRejected(rtCd = "7", msg = "원주문정보가 존재하지않습니다."))
        every { kisOrderPort.cancel(live, live.mode) } returns OrderResult.Right(Unit)

        val result = cancelRestingSells()

        // expired는 already-cancelled, live는 정상 취소 — 둘 다 종결.
        verify(exactly = 1) { orderRepo.markCompleted(507L, any(), OrderStatus.CANCELLED) }
        verify(exactly = 1) { orderRepo.markCompleted(508L, any(), OrderStatus.CANCELLED) }
        verify(exactly = 2) { cancelledProducer.publish(any()) }
        assertThat(result).isInstanceOf(RestingSellOrderCanceller.Result.Cancelled::class.java)
        assertThat((result as RestingSellOrderCanceller.Result.Cancelled).cancelledOrders).hasSize(2)
        assertThat(result.canProceed).isTrue()
    }

    private fun cancelRestingSells(): RestingSellOrderCanceller.Result =
        canceller.cancelRestingSells(
            orderRepo = orderRepo,
            userId = userId,
            strategyId = strategyId,
            code = code,
        )

    private fun openSell(
        id: Long,
        idempotencyKey: String = "auto-tp:xyz",
        source: OrderSource = OrderSource.AUTO,
        strategyId: Long = this.strategyId,
        ackPresent: Boolean = true,
    ): OrderRequest =
        OrderRequest(
            id = id,
            userId = userId,
            signalId = 0L,
            strategyId = strategyId,
            code = code,
            side = OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = 2,
            limitPrice = BigDecimal("138600"),
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0029796100" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = idempotencyKey,
            requestedAt = Instant.parse("2026-06-22T06:15:00Z"),
            source = source,
        )
}

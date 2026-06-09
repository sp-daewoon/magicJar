package kr.spdaewoon.magicjar.consumer.order

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.OrderSide
import kr.spdaewoon.magicjar.domain.order.execution.OrderSource
import kr.spdaewoon.magicjar.domain.order.execution.OrderStatus
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import kr.spdaewoon.magicjar.domain.order.execution.port.ExecutionStrategyPositionUpdatePort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderExecutionRepository
import kr.spdaewoon.magicjar.domain.position.StrategyPosition
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.Instant

class StrategyPositionExecutionReplayServiceTest {
    private val executionRepository = mockk<OrderExecutionRepository>()
    private val updatePort = mockk<ExecutionStrategyPositionUpdatePort>(relaxed = true)
    private val strategyPositionPort = mockk<StrategyPositionPort>()
    private val service =
        StrategyPositionExecutionReplayService(
            orderExecutionRepositoryProvider = provider(executionRepository),
            strategyPositionUpdatePortProvider = provider(updatePort),
            strategyPositionPortProvider = provider(strategyPositionPort),
        )

    @Test
    fun `strategy_position row가 없으면 저장된 체결들을 원 주문 기준으로 재생한다`() {
        val request = request()
        val executions = listOf(execution(id = 10L), execution(id = 11L))
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returns null
        every { executionRepository.findByOrderId(99L) } returns executions

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isEqualTo(2)
        verify(exactly = 1) { updatePort.applyExecution(request, executions[0]) }
        verify(exactly = 1) { updatePort.applyExecution(request, executions[1]) }
    }

    @Test
    fun `strategy_position row가 이미 있으면 중복 재생하지 않는다`() {
        val request = request()
        every { strategyPositionPort.findByUserStrategyCode("12345678", 100L, "005930") } returns
            StrategyPosition(
                userId = "12345678",
                strategyId = 100L,
                code = "005930",
                quantity = 1,
                avgPrice = BigDecimal("78000"),
                openedAt = kotlinx.datetime.Instant.fromEpochSeconds(1747094405L),
                updatedAt = kotlinx.datetime.Instant.fromEpochSeconds(1747094405L),
            )

        val replayed = service.replayIfMissing(request, "TEST")

        assertThat(replayed).isZero()
        verify(exactly = 0) { executionRepository.findByOrderId(any()) }
        verify(exactly = 0) { updatePort.applyExecution(any(), any()) }
    }

    private fun request(): OrderRequest =
        OrderRequest(
            id = 99L,
            userId = "12345678",
            signalId = 42L,
            strategyId = 100L,
            code = "005930",
            side = OrderSide.BUY,
            priceType = OrderPriceType.MARKET,
            quantity = 2,
            limitPrice = null,
            status = OrderStatus.ACK,
            mode = TradingMode.MOCK,
            kisOrderNo = "ODNO123",
            idempotencyKey = "42:12345678",
            requestedAt = Instant.ofEpochSecond(1747094405L),
            source = OrderSource.AUTO,
        )

    private fun execution(id: Long): OrderExecution =
        OrderExecution(
            id = id,
            orderId = 99L,
            userId = "12345678",
            kisOrderNo = "ODNO123",
            executedQuantity = 1,
            executedPrice = BigDecimal("78000"),
            executedAt = Instant.ofEpochSecond(1747094406L),
            eventId = "ODNO123:$id",
            createdAt = Instant.ofEpochSecond(1747094406L),
        )

    private fun <T : Any> provider(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }
}

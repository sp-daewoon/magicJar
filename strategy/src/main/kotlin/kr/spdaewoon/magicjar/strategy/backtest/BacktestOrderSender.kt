package kr.spdaewoon.magicjar.strategy.backtest

import kr.spdaewoon.magicjar.core.domain.Mode
import kr.spdaewoon.magicjar.core.domain.Order
import kr.spdaewoon.magicjar.core.domain.OrderResult
import kr.spdaewoon.magicjar.core.domain.OrderStatus
import kr.spdaewoon.magicjar.core.event.OrderFilledEvent
import kr.spdaewoon.magicjar.core.port.OrderSender
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.Instant

/**
 * 백테스트 모드용 OrderSender. 가상 체결을 즉시 수행한다.
 *
 * - MARKET 주문: 호출 시점의 `nextBarOpen`(엔진이 주입)에 슬리피지 적용.
 * - LIMIT 주문: 그대로 체결(MVP는 단순화).
 *
 * 본 클래스는 정적 가격을 알 수 없으므로 [BacktestExecutionContext]를 ThreadLocal로 받아
 * 다음 봉 시가·슬리피지·수수료를 계산한다. 백테스트 엔진이 매 봉 진입 전 [setContext]를 호출.
 */
@Component
@ConditionalOnProperty(prefix = "magicjar", name = ["mode"], havingValue = "BACKTEST")
class BacktestOrderSender(
    private val publisher: ApplicationEventPublisher,
    private val clock: Clock = Clock.systemUTC(),
) : OrderSender {
    override fun send(order: Order): OrderResult {
        val ctx =
            BacktestExecutionContextHolder.current()
                ?: return reject(order, "BacktestExecutionContext is missing")
        val basePrice =
            ctx.nextBarOpen ?: order.price
                ?: return reject(order, "no execution price available")
        val slippageMultiplier =
            if (order.side == kr.spdaewoon.magicjar.core.domain.Side.BUY) {
                BigDecimal.ONE.add(ctx.slippageBp.divide(BigDecimal("10000"), java.math.MathContext.DECIMAL64))
            } else {
                BigDecimal.ONE.subtract(ctx.slippageBp.divide(BigDecimal("10000"), java.math.MathContext.DECIMAL64))
            }
        val executionPrice = basePrice.multiply(slippageMultiplier).setScale(2, java.math.RoundingMode.HALF_UP)
        val filledAt = ctx.fillAt ?: Instant.now(clock)
        val result =
            OrderResult(
                order = order.copy(status = OrderStatus.FILLED),
                resultStatus = OrderStatus.FILLED,
                filledQty = order.qty,
                avgFillPrice = executionPrice,
                filledAt = filledAt,
            )
        publisher.publishEvent(OrderFilledEvent(result, Mode.BACKTEST))
        return result
    }

    private fun reject(
        order: Order,
        reason: String,
    ): OrderResult =
        OrderResult(
            order = order.copy(status = OrderStatus.REJECTED),
            resultStatus = OrderStatus.REJECTED,
            filledQty = 0,
            avgFillPrice = null,
            filledAt = null,
            rejectReason = reason,
        )
}

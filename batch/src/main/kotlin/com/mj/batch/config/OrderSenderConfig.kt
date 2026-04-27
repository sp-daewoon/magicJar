package com.mj.batch.config

import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.port.OrderSender
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * BacktestOrderSender가 @ConditionalOnProperty(mode=BACKTEST)로만 등록되므로,
 * 그 외 모드에서는 플레이스홀더가 폴백.
 */
@Configuration
class OrderSenderConfig {
    @Bean
    @ConditionalOnMissingBean(OrderSender::class)
    fun placeholderOrderSender(): OrderSender = BatchPlaceholderOrderSender
}

object BatchPlaceholderOrderSender : OrderSender {
    private val log = LoggerFactory.getLogger(BatchPlaceholderOrderSender::class.java)

    override fun send(order: Order): OrderResult {
        log.warn("BatchPlaceholderOrderSender invoked — order={}", order.id)
        return OrderResult(
            order = order,
            resultStatus = OrderStatus.REJECTED,
            filledQty = 0,
            avgFillPrice = null,
            filledAt = null,
            rejectReason = "OrderSender 구현체 미등록 (batch P2 placeholder)",
        )
    }
}

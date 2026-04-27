package com.mj.consumer.config

import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.port.OrderSender
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `magicjar.mode` 프로퍼티에 따라 OrderSender 빈을 선택한다 (ADR 0006).
 *
 * BACKTEST 모드에서는 batch의 BacktestOrderSender가 사용되므로,
 * consumer에서는 MOCK/REAL 모드 또는 폴백 no-op만 처리한다.
 */
@Configuration
class OrderSenderConfig {
    @Bean
    @ConditionalOnMissingBean(OrderSender::class)
    fun placeholderOrderSender(): OrderSender = PlaceholderOrderSender
}

object PlaceholderOrderSender : OrderSender {
    private val log = LoggerFactory.getLogger(PlaceholderOrderSender::class.java)

    override fun send(order: Order): OrderResult {
        log.warn("PlaceholderOrderSender invoked — 실제 구현 빈이 아직 등록되지 않았습니다. order={}", order.id)
        return OrderResult(
            order = order,
            resultStatus = OrderStatus.REJECTED,
            filledQty = 0,
            avgFillPrice = null,
            filledAt = null,
            rejectReason = "OrderSender 구현체 미등록 (P2 placeholder)",
        )
    }
}

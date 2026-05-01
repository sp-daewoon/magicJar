package com.mj.consumer.config

import com.mj.consumer.application.guard.GuardChain
import com.mj.consumer.application.order.KisOrderSender
import com.mj.consumer.application.order.MockOrderSender
import com.mj.domain.order.port.KisOrderPort
import com.mj.domain.order.port.OrderEventRepository
import com.mj.domain.order.port.OrderRepository
import com.mj.domain.order.port.OrderSender
import com.mj.domain.user.port.KisCredentialProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile

/**
 * OrderSender profile 분기. spec §3 D6, ADR 0006.
 *
 * - mock: in-memory MockOrderSender (FILLED 즉시 시뮬, 외부 호출 0)
 * - dev/prd: KisOrderSender (Guard chain + KisOrderPort + 영속)
 *
 * BACKTEST 모드는 batch 모듈의 BacktestOrderSender가 별도로 등록 — consumer에선 처리하지 않음.
 */
@Configuration
class OrderSenderConfig {
    @Bean
    @Profile("mock")
    @ConditionalOnMissingBean(OrderSender::class)
    fun mockOrderSender(): OrderSender = MockOrderSender()

    @Bean
    @Profile("dev | prd")
    @ConditionalOnMissingBean(OrderSender::class)
    fun kisOrderSender(
        kisOrderPort: KisOrderPort,
        guard: GuardChain,
        orders: OrderRepository,
        orderEvents: OrderEventRepository,
        credentials: KisCredentialProvider,
    ): OrderSender = KisOrderSender(kisOrderPort, guard, orders, orderEvents, credentials)
}

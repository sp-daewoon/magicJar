package com.mj.consumer.config

import com.mj.consumer.application.order.KisOrderSender
import com.mj.consumer.application.order.MockOrderSender
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.mockk
import org.junit.jupiter.api.Test

/**
 * OrderSenderConfig profile 분기 단위 검증.
 *
 * Spring 전체 부팅 e2e는 testcontainers postgres·Kafka 의존 — sandbox 한계로 host docker
 * 환경 또는 T19~T20 dogfooding에서 별도 검증.
 */
class OrderSenderConfigTest {
    private val cfg = OrderSenderConfig()

    @Test
    fun `mockOrderSender bean is MockOrderSender`() {
        cfg.mockOrderSender().shouldBeInstanceOf<MockOrderSender>()
    }

    @Test
    fun `kisOrderSender bean is KisOrderSender`() {
        cfg
            .kisOrderSender(
                kisOrderPort = mockk(),
                guard = mockk(),
                orders = mockk(),
                orderEvents = mockk(),
                credentials = mockk(),
            ).shouldBeInstanceOf<KisOrderSender>()
    }
}

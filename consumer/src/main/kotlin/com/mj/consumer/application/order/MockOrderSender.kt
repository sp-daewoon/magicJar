package com.mj.consumer.application.order

import com.mj.domain.common.Order
import com.mj.domain.common.OrderResult
import com.mj.domain.common.OrderStatus
import com.mj.domain.order.port.OrderSender
import org.slf4j.LoggerFactory
import java.time.Instant

/**
 * mock profile용 in-memory FILLED 시뮬 OrderSender.
 * spec §3 D6. 단위테스트·CI·로컬 dev 검증용 — KIS 외부 호출 0.
 *
 * LIMIT은 입력 가격, MARKET은 가격 0.
 * OrderResult.FILLED를 즉시 반환 (실 거래의 비동기 흐름은 시뮬 안 함).
 */
class MockOrderSender : OrderSender {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun send(order: Order): OrderResult {
        log.info(
            "[MOCK_SENDER] simulated FILLED — order_id={} code={} side={} qty={} price={}",
            order.id,
            order.code,
            order.side,
            order.qty,
            order.price,
        )
        return OrderResult(
            order = order,
            resultStatus = OrderStatus.FILLED,
            filledQty = order.qty,
            avgFillPrice = order.price ?: java.math.BigDecimal.ZERO,
            filledAt = Instant.now(),
            rejectReason = null,
        )
    }
}

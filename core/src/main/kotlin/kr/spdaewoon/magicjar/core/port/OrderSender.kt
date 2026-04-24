package kr.spdaewoon.magicjar.core.port

import kr.spdaewoon.magicjar.core.domain.Order
import kr.spdaewoon.magicjar.core.domain.OrderResult

/**
 * 주문 전송 포트. 구현체는 `magicjar.mode`에 따라 BACKTEST/MOCK/REAL 중 하나로 분기된다 (ADR 0006).
 * - BACKTEST: strategy 모듈의 `BacktestOrderSender`
 * - MOCK: order-exec 모듈의 `MockOrderSender` (KIS 모의계좌)
 * - REAL: order-exec 모듈의 `RealOrderSender` (KIS 실계좌) — OrderGuard 데코레이터 필수
 */
interface OrderSender {
    fun send(order: Order): OrderResult
}

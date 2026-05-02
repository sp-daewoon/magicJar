package kr.spdaewoon.magicjar.config

import kr.spdaewoon.magicjar.core.domain.Order
import kr.spdaewoon.magicjar.core.domain.OrderResult
import kr.spdaewoon.magicjar.core.domain.OrderStatus
import kr.spdaewoon.magicjar.core.port.OrderSender
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * `magicjar.mode` 프로퍼티에 따라 OrderSender 빈을 선택한다 (ADR 0006).
 * - BACKTEST → strategy 모듈의 BacktestOrderSender (Phase B-2에서 주입)
 * - MOCK     → order-exec 모듈의 MockOrderSender (Phase B-2에서 주입)
 * - REAL     → order-exec 모듈의 RealOrderSender + OrderGuard (Phase B-2에서 주입)
 *
 * Phase B-1(스캐폴딩) 시점에는 실제 구현체가 없으므로 임시 No-op 빈을 등록해 부팅만 가능하게 한다.
 * 각 모듈이 실제 빈을 등록하면 `@ConditionalOnMissingBean`에 의해 자동 대체된다.
 */
@Configuration
class OrderSenderConfig {
    @Bean
    @ConditionalOnMissingBean(OrderSender::class)
    fun placeholderOrderSender(): OrderSender = PlaceholderOrderSender
}

/**
 * TODO(Phase B-2): strategy/order-exec 모듈이 실제 OrderSender 빈을 제공하면 이 플레이스홀더는 제거된다.
 */
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
            rejectReason = "OrderSender 구현체 미등록 (Phase B-1 placeholder)",
        )
    }
}

/**
 * TODO(Phase B-2): strategy/web/ai 모듈이 동일 인터페이스 빈을 제공하면 플레이스홀더 제거.
 *   - CandleRepository   → market-data
 *   - InstrumentCatalog  → market-data
 *   - NewsRepository     → market-data
 *   - StrategyEvaluator  → strategy
 *   - TradeJournalRepository → strategy
 *   - HarnessStateReader → web
 *   - BrokerGateway      → market-data
 */

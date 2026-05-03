package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.order.OrderJpaAdapter
import com.mj.domain.common.Order
import com.mj.domain.common.Side
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.util.UUID
import org.springframework.core.annotation.Order as SpringOrder

/**
 * 종목당 포지션 비중 한도 가드. ADR 0042 @Order(220).
 *
 * 흐름:
 * 1. 현재 종목 포지션 평가액 조회 (orders 테이블 BUY-SELL 체결합)
 * 2. 신규 주문 예상 금액 더하면 종목 비중이 (전체 자본 × limit_percent) 초과하는지 확인
 * 3. 초과 시 reject RejectReason.PositionLimitExceeded
 *
 * 1차 default: 종목당 10% — 정상 dogfooding·고빈도 전략 절대 막히지 않는 느슨한 값.
 * env property로 운영 시 조정 가능.
 *
 * profile mock에서 Bean 미등록 (MarketSessionGuard 동일 패턴).
 *
 * 설정 키:
 * - magicjar.guard.position-limit.enabled       (default true, dev/prd만)
 * - magicjar.guard.position-limit.limit-percent (default 0.10 = 10%)
 * - magicjar.guard.position-limit.total-capital (default 10,000,000 KRW)
 */
@Component
@Profile("dev | prd")
@SpringOrder(220)
class PositionLimitGuard(
    private val orderJpa: OrderJpaAdapter,
    @Value("\${magicjar.guard.position-limit.limit-percent:0.10}")
    private val limitPercent: Double,
    /** 전체 자본 추정액. 계좌 잔고 조회 없이 설정값으로 1차 운영. */
    @Value("\${magicjar.guard.position-limit.total-capital:10000000}")
    private val totalCapitalKrw: Long,
) : OrderGuard {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(
        userId: UUID,
        accountNo: String,
        order: Order,
    ): GuardDecision {
        // SELL 주문은 포지션 축소 — 비중 초과 불가, 항상 PASS
        if (order.side == Side.SELL) return GuardDecision.Pass
        // 가격 미지정(MARKET) 주문은 체결가 미확정 — 보수적 PASS (KIS가 최종 검증)
        val price = order.price ?: return GuardDecision.Pass

        val totalCapital = BigDecimal.valueOf(totalCapitalKrw)
        val limitValue = totalCapital.multiply(BigDecimal.valueOf(limitPercent)).setScale(2, RoundingMode.HALF_UP)

        val currentPositionValue = orderJpa.positionValue(userId, order.code)
        val orderValue = price.multiply(BigDecimal.valueOf(order.qty.toLong()))
        val projectedValue = currentPositionValue.add(orderValue)

        return if (projectedValue > limitValue) {
            val reason =
                com.mj.domain.order.RejectReason.PositionLimitExceeded(
                    attemptedQty = order.qty,
                    currentPositionValue = currentPositionValue,
                    limitPercent = limitPercent,
                    totalCapital = totalCapital,
                )
            log.warn(
                "[POSITION_LIMIT] REJECT code={} current={} +order={} projected={} limit={} ({}%)",
                order.code,
                currentPositionValue.toPlainString(),
                orderValue.toPlainString(),
                projectedValue.toPlainString(),
                limitValue.toPlainString(),
                (limitPercent * 100).toInt(),
            )
            GuardDecision.Reject(reason.toDetail())
        } else {
            log.debug(
                "[POSITION_LIMIT] PASS code={} projected={} / limit={}",
                order.code,
                projectedValue.toPlainString(),
                limitValue.toPlainString(),
            )
            GuardDecision.Pass
        }
    }
}

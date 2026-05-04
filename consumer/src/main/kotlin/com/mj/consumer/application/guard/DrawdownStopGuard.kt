package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.order.OrderJpaAdapter
import com.mj.domain.common.Order
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Profile
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import org.springframework.core.annotation.Order as SpringOrder

/**
 * 일일 Drawdown 한도 가드. ADR 0042 @Order(240).
 *
 * 흐름:
 * 1. 오늘 KST 일일 실현 P&L 조회 (FILLED orders SELL금액 - BUY금액)
 * 2. P&L / totalCapital 로 손실률 계산
 * 3. 손실률이 limit_percent 초과 시 reject RejectReason.DrawdownStopTriggered
 *
 * 1차 default: 일일 -3% — 정상 dogfooding 절대 막히지 않는 느슨한 값.
 * env property로 운영 시 조정 가능.
 *
 * profile mock에서 Bean 미등록 (MarketSessionGuard 동일 패턴).
 *
 * 설정 키:
 * - magicjar.guard.drawdown-stop.limit-percent (default -0.03 = -3%)
 * - magicjar.guard.drawdown-stop.total-capital (default 10,000,000 KRW)
 *
 * NOTE: PerformanceCalculator(T14)는 히스토리컬 trades 기반 — 실시간 Guard용으로는
 *       orders 테이블 당일 FILLED 집계가 적합 (DB 1쿼리, 트랜잭션 내 일관성).
 */
@Component
@Profile("dev | prd")
@SpringOrder(240)
class DrawdownStopGuard(
    private val orderJpa: OrderJpaAdapter,
    /** 음수 값. 예: -0.03 = 일일 -3% 손실 한도. */
    @Value("\${magicjar.guard.drawdown-stop.limit-percent:-0.03}")
    private val limitPercent: Double,
    @Value("\${magicjar.guard.drawdown-stop.total-capital:10000000}")
    private val totalCapitalKrw: Long,
) : OrderGuard {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mc = MathContext(10, RoundingMode.HALF_UP)

    override fun check(
        userId: String,
        accountNo: String,
        order: Order,
    ): GuardDecision {
        val totalCapital = BigDecimal.valueOf(totalCapitalKrw)
        if (totalCapital.signum() <= 0) return GuardDecision.Pass

        val todayPnl = orderJpa.todayRealizedPnl(userId)
        // P&L이 양수이면 손실 없음 — 항상 PASS
        if (todayPnl.signum() >= 0) return GuardDecision.Pass

        val drawdownRate = todayPnl.divide(totalCapital, mc).toDouble()

        return if (drawdownRate < limitPercent) {
            val reason =
                com.mj.domain.order.RejectReason.DrawdownStopTriggered(
                    currentDrawdownPercent = drawdownRate,
                    limitPercent = limitPercent,
                )
            log.warn(
                "[DRAWDOWN_STOP] REJECT today_pnl={} drawdown={}% limit={}%",
                todayPnl.toPlainString(),
                String.format("%.2f", drawdownRate * 100),
                String.format("%.2f", limitPercent * 100),
            )
            GuardDecision.Reject(reason.toDetail())
        } else {
            log.debug(
                "[DRAWDOWN_STOP] PASS today_pnl={} drawdown={}% / limit={}%",
                todayPnl.toPlainString(),
                String.format("%.2f", drawdownRate * 100),
                String.format("%.2f", limitPercent * 100),
            )
            GuardDecision.Pass
        }
    }
}

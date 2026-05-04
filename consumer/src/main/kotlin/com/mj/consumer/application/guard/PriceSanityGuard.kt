package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import com.mj.domain.common.Order
import com.mj.domain.common.OrderType
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.temporal.ChronoUnit
import org.springframework.core.annotation.Order as SpringOrder

/**
 * fat-finger 차단. 전일 종가 ±30% 벗어난 LIMIT 주문 거부.
 *
 * MARKET 주문은 가격 검증 대상 아님 (KIS가 자체 호가 한도 적용).
 * 전일 종가 조회 실패(시드 부재 등)는 보수적으로 통과 (KIS가 최종 검증).
 *
 * GuardChain 세 번째 자리(@Order 300).
 */
@Component
@SpringOrder(300)
class PriceSanityGuard(
    private val candles: CandleJpaRepository,
) : OrderGuard {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(
        userId: String,
        accountNo: String,
        order: Order,
    ): GuardDecision {
        if (order.type != OrderType.LIMIT) return GuardDecision.Pass
        val price = order.price ?: return GuardDecision.Pass

        val now = Instant.now()
        val from = now.minus(7, ChronoUnit.DAYS)
        val recent =
            candles
                .findByCodeAndIntervalAndOpenTimeBetween(
                    order.code,
                    Interval.D1,
                    from,
                    now,
                ).maxByOrNull { it.openTime }

        if (recent == null) {
            log.debug("[GUARD] no D1 candle found for code={} — pass", order.code)
            return GuardDecision.Pass
        }

        val close = recent.close
        val lower = close.multiply(BigDecimal("0.7")).setScale(2, RoundingMode.HALF_UP)
        val upper = close.multiply(BigDecimal("1.3")).setScale(2, RoundingMode.HALF_UP)
        return if (price < lower || price > upper) {
            GuardDecision.Reject(
                "PRICE_OUT_OF_RANGE code=${order.code} price=$price prev_close=$close range=[$lower,$upper]",
            )
        } else {
            GuardDecision.Pass
        }
    }
}

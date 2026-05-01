package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.guard.DailyOrderCounterJpaAdapter
import com.mj.domain.common.Order
import com.mj.domain.common.OrderType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import org.springframework.core.annotation.Order as SpringOrder

/**
 * 일일 주문 횟수·누적 거래대금 한도. spec §3 D4.
 *
 * 한도값은 매우 느슨한 default. 정상 dogfooding·고빈도 전략(스켈핑·호가 펼침)은 절대 막히지 않게.
 * env property로 운영 시 조정 가능.
 *
 * - magicjar.guard.daily-order-limit-count        (default 1000)
 * - magicjar.guard.daily-order-limit-value-krw    (default 1억원)
 *
 * GuardChain 두 번째 자리(@Order 200).
 */
@Component
@SpringOrder(200)
class DailyOrderLimitGuard(
    private val counter: DailyOrderCounterJpaAdapter,
    @Value("\${magicjar.guard.daily-order-limit-count:1000}")
    private val maxCount: Int,
    @Value("\${magicjar.guard.daily-order-limit-value-krw:100000000}")
    private val maxValueKrw: Long,
    private val clock: Clock = Clock.system(ZoneId.of("Asia/Seoul")),
) : OrderGuard {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun check(
        userId: UUID,
        accountNo: String,
        order: Order,
    ): GuardDecision {
        val today = LocalDate.now(clock)
        val current = counter.current(today, userId, accountNo)
        val currentCount = current?.count ?: 0
        val currentValue = current?.totalValueKrw ?: BigDecimal.ZERO

        if (currentCount >= maxCount) {
            return GuardDecision.Reject("DAILY_LIMIT_COUNT_EXCEEDED current=$currentCount max=$maxCount")
        }
        // MARKET 주문은 price=null이라 추정 거래대금 0으로 처리 → 카운트만 증가
        val orderValue =
            (order.price ?: BigDecimal.ZERO).multiply(BigDecimal.valueOf(order.qty.toLong()))
        if (currentValue.add(orderValue).toLong() > maxValueKrw) {
            return GuardDecision.Reject(
                "DAILY_LIMIT_VALUE_EXCEEDED current=${currentValue.toLong()} +order=${orderValue.toLong()} max=$maxValueKrw",
            )
        }

        // 통과 시 카운터 증가 (atomic UPSERT). 실패 시 호출자 트랜잭션이 rollback해야 함.
        counter.increment(today, userId, accountNo, orderValue)
        if (order.type == OrderType.MARKET) {
            log.debug("[GUARD] MARKET order — value 추정 0, count만 증가")
        }
        return GuardDecision.Pass
    }
}

package com.mj.consumer.application.guard

import com.mj.consumer.infrastructure.persistence.guard.KillSwitchJpaAdapter
import com.mj.domain.common.Order
import org.springframework.stereotype.Component
import org.springframework.core.annotation.Order as SpringOrder

/**
 * KillSwitch — 모든 placeOrder를 차단하는 비상 정지. spec §3 D4.
 *
 * default ON으로 부팅 — 사용자가 매 운영 시작 시 명시적으로 OFF.
 * 운영 가이드의 "응급상황 대응" 절차 1번이 KillSwitch ON.
 *
 * GuardChain 첫 자리(@Order 100)에서 평가.
 */
@Component
@SpringOrder(100)
class KillSwitchGuard(
    private val state: KillSwitchJpaAdapter,
) : OrderGuard {
    override fun check(
        userId: String,
        accountNo: String,
        order: com.mj.domain.common.Order,
    ): GuardDecision =
        if (state.isActive("GLOBAL")) {
            GuardDecision.Reject("KILL_SWITCH_ON")
        } else {
            GuardDecision.Pass
        }
}

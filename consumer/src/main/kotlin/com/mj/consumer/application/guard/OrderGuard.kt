package com.mj.consumer.application.guard

import com.mj.domain.common.Order
import java.util.UUID

/**
 * 주문 사전 가드. spec §3 D4.
 *
 * 1차 방벽은 Order 도메인 invariants(qty>0, LIMIT 시 price 필수 등). Guard는 비상 정지 성격의
 * 2차 안전망 — 정상 흐름에선 invisible, 폭주·실수만 차단.
 *
 * 한도값은 매우 느슨하게 잡고 env property로 노출 — 운영 가이드(_workspace/operations/order-policy.md)
 * 에서 단계적 조정.
 */
sealed interface GuardDecision {
    data object Pass : GuardDecision

    data class Reject(
        val reason: String,
    ) : GuardDecision
}

interface OrderGuard {
    fun check(
        userId: UUID,
        accountNo: String,
        order: Order,
    ): GuardDecision
}

/**
 * Composite — 등록된 모든 Guard를 순서대로 호출. 첫 Reject에서 즉시 short-circuit.
 */
class GuardChain(
    private val guards: List<OrderGuard>,
) : OrderGuard {
    override fun check(
        userId: UUID,
        accountNo: String,
        order: Order,
    ): GuardDecision {
        guards.forEach { g ->
            when (val d = g.check(userId, accountNo, order)) {
                is GuardDecision.Reject -> return d
                GuardDecision.Pass -> {}
            }
        }
        return GuardDecision.Pass
    }
}

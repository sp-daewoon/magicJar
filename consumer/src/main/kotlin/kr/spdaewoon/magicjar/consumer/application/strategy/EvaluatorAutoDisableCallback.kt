package kr.spdaewoon.magicjar.consumer.application.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason

/**
 * 평가기 → StrategyService 비동기 콜백 인터페이스.
 *
 * Source: spec §6-3 (consumer evaluator 책임 분리).
 *
 * - JvmStrategyEvaluator는 timeout / CPU budget 초과 시 본 콜백 호출
 * - 실제 구현은 backend-core 영역 (api/.../application/strategy/StrategyService) — DB update + risk.violation.v1 publish
 * - backend-core 미합류 시 [NoopAutoDisableCallback]가 default bean (graceful no-op)
 *
 * spec §6-3 LOCK.
 */
fun interface EvaluatorAutoDisableCallback {
    fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    )
}

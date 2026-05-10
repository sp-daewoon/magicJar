package kr.spdaewoon.magicjar.consumer.application.strategy

import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * [EvaluatorAutoDisableCallback] default no-op 구현.
 *
 * backend-core PR-36b 합류 전 — `StrategyService` bean이 없을 때 graceful 동작 보장.
 * 합류 후 `@Service StrategyService : EvaluatorAutoDisableCallback`이 우선 (`@ConditionalOnMissingBean`).
 *
 * spec §6-3 LOCK.
 */
@Component
class NoopAutoDisableCallback : EvaluatorAutoDisableCallback {
    private val log = LoggerFactory.getLogger(javaClass)

    override fun onAutoDisable(
        strategyId: Long,
        reason: StrategyDisabledReason,
    ) {
        log.warn(
            "NoopAutoDisableCallback.onAutoDisable(strategyId={}, reason={}) — backend-core StrategyService 미합류, no-op.",
            strategyId,
            reason,
        )
    }
}

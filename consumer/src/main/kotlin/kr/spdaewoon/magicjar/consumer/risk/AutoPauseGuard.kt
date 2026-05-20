package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Auto-pause 가드 — 연속 BLOCKED N회 시 strategy AUTO_PAUSED (ADR 0067 §7-1 + F-1 정합).
 *
 * 임계 도달 시 [EvaluatorAutoDisableCallback.onAutoDisable] 호출 — Phase 2 backend-core가
 * strategy.status=PAUSED + StrategyCache evict + STOMP AUTO_PAUSED 알림. Phase 2 미합류 시
 * [kr.spdaewoon.magicjar.strategy.NoopAutoDisableCallback] graceful no-op.
 *
 * spec 인용: ADR 0067 §7-1 AutoPauseGuard + §7-2 단계 5 + F-1 4단 처리.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class AutoPauseGuard(
    private val riskEventRepositoryProvider: ObjectProvider<RiskEventRepository>,
    private val autoDisableCallback: EvaluatorAutoDisableCallback,
) {
    private val log = LoggerFactory.getLogger(AutoPauseGuard::class.java)

    companion object {
        /** BLOCKED 누적 검사 window (분) — ADR 0067 §2-6 threshold 산출 사유 정합. */
        const val DEFAULT_WINDOW_MINUTES = 10L
    }

    /**
     * strategy 연속 BLOCKED 임계 검사 + 도달 시 AUTO_PAUSE callback 호출.
     *
     * @return true — AUTO_PAUSE 발동 · false — 미발동
     */
    fun checkAndPauseIfNeeded(
        strategyId: Long,
        autoPauseThreshold: Int,
    ): Boolean {
        val repo = riskEventRepositoryProvider.getIfAvailable()
        if (repo == null) {
            log.debug(
                "AutoPauseGuard RiskEventRepository 미합류 — skip (strategyId={})",
                strategyId,
            )
            return false
        }

        val recentBlocked =
            try {
                repo.countRecentBlockedByStrategy(strategyId, DEFAULT_WINDOW_MINUTES)
            } catch (e: Exception) {
                log.warn(
                    "AutoPauseGuard countRecentBlockedByStrategy 실패 (skip): strategyId={} err={}",
                    strategyId,
                    e.message,
                )
                return false
            }

        if (recentBlocked >= autoPauseThreshold) {
            log.warn(
                "AutoPauseGuard threshold 도달 — AUTO_PAUSE 발동: strategyId={} recentBlocked={} threshold={}",
                strategyId,
                recentBlocked,
                autoPauseThreshold,
            )
            try {
                // ADR 0067 F-1 정합 — Phase 2 backend-core가 strategy.status=PAUSED + StrategyCache evict + STOMP 알림.
                // StrategyDisabledReason enum 신규 RISK_VIOLATION row는 Phase 2 권한 —
                // Phase 1은 기존 MANUAL 값으로 graceful fallback (audit log + risk_event 본문이 사유 식별).
                autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.MANUAL)
            } catch (e: Exception) {
                log.error(
                    "AutoPauseGuard onAutoDisable 호출 실패: strategyId={} err={}",
                    strategyId,
                    e.message,
                )
            }
            return true
        }
        return false
    }
}

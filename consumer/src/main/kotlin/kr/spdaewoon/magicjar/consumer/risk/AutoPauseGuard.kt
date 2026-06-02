package kr.spdaewoon.magicjar.consumer.risk

import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.ObjectProvider
import org.springframework.stereotype.Component

/**
 * Auto-pause 가드 — 연속 BLOCKED N회 시 strategy 신규 BUY 차단(BUY_BLOCKED).
 *
 * 임계 도달 시 [EvaluatorAutoDisableCallback.onAutoDisable] 호출. RISK_VIOLATION은 전체 평가 중지가 아니라
 * BUY_BLOCKED 상태로 전환되어 SELL/익절/손절 평가는 계속 유지한다.
 *
 * spec 인용: ADR 0067 §7-1 AutoPauseGuard + §7-2 단계 5 + F-1 보호 처리.
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
     * strategy 연속 BLOCKED 임계 검사 + 도달 시 신규 BUY 차단 callback 호출.
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
                "AutoPauseGuard threshold 도달 — 신규 BUY 차단 발동: strategyId={} recentBlocked={} threshold={}",
                strategyId,
                recentBlocked,
                autoPauseThreshold,
            )
            try {
                // ADR 0067 F-1 정합 — risk 차단 누적은 사용자 화면에서 구분 가능한 사유로 남긴다.
                autoDisableCallback.onAutoDisable(strategyId, StrategyDisabledReason.RISK_VIOLATION)
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

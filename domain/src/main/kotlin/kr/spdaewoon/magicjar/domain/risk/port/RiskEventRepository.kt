package kr.spdaewoon.magicjar.domain.risk.port

import kr.spdaewoon.magicjar.domain.risk.RiskEvent

/**
 * RiskEvent 영속 포트 (ADR 0067 §5-5 + G-1). 헌법 §6 abstract만.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
interface RiskEventRepository {
    fun save(event: RiskEvent): RiskEvent

    /**
     * strategy당 최근 N분 내 BLOCKED 카운트 (AutoPauseGuard 임계 검사용 — F-1).
     */
    fun countRecentBlockedByStrategy(
        strategyId: Long,
        windowMinutes: Long,
    ): Int
}

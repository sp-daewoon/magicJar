package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * AutoPauseGuard 단위 테스트 (ADR 0067 §7-1 + F-1 정합).
 *
 * 매트릭스:
 *  1. threshold 미달 → false 반환 (callback 미호출)
 *  2. threshold 도달 → true 반환 + callback 호출 (RISK_VIOLATION reason)
 *  3. RiskEventRepository 미합류(Phase 2 부재) → false skip
 *
 * AI 호출 0 — T-RISK 트랙 정책.
 */
class AutoPauseGuardTest {
    private val riskEventRepository = mockk<RiskEventRepository>(relaxed = true)
    private val provider =
        mockk<ObjectProvider<RiskEventRepository>>().also {
            every { it.getIfAvailable() } returns riskEventRepository
        }
    private val callback = mockk<EvaluatorAutoDisableCallback>(relaxed = true)
    private val guard = AutoPauseGuard(provider, callback)

    @Test
    fun `threshold 미달 시 false 반환 — callback 미호출`() {
        every {
            riskEventRepository.countRecentBlockedByStrategy(strategyId = 100L, windowMinutes = 10L)
        } returns 5

        val result = guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `threshold 도달 시 true 반환 + onAutoDisable 호출`() {
        every {
            riskEventRepository.countRecentBlockedByStrategy(strategyId = 100L, windowMinutes = 10L)
        } returns 10

        val result = guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            callback.onAutoDisable(strategyId = 100L, reason = StrategyDisabledReason.RISK_VIOLATION)
        }
    }

    @Test
    fun `RiskEventRepository 미합류 시 false skip`() {
        val emptyProvider =
            mockk<ObjectProvider<RiskEventRepository>>().also {
                every { it.getIfAvailable() } returns null
            }
        val skipGuard = AutoPauseGuard(emptyProvider, callback)

        val result = skipGuard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }
}

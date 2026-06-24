package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.risk.AutoPauseGuard.Companion.AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES
import kr.spdaewoon.magicjar.consumer.risk.AutoPauseGuard.Companion.BENIGN_AUTO_PAUSE_EXCLUDED_RULES
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.strategy.EvaluatorAutoDisableCallback
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * AutoPauseGuard 단위 테스트 (ADR 0067 §7-1 + F-1 + F9 + ADR 0129 레버5 정합).
 *
 * 매트릭스:
 *  1. threshold 미달 → false 반환 (callback 미호출)
 *  2. threshold 도달 → true 반환 + callback 호출 (RISK_VIOLATION reason)
 *  3. RiskEventRepository 미합류(Phase 2 부재) → false skip
 *  4. [F9] 무해 가드 rule 제외 set 을 repo 카운트에 전달한다 (+ ADR 0129 자기억제 룰 합류)
 *  5. [F9 회귀] 무해 rule 제외 후 카운트가 임계 미만이면 정지 0 (정상 거동이 자동정지 안 함)
 *  6. [F9 회귀] 위험 rule 카운트가 임계 도달이면 정지 발동 (자본 위험 신호는 그대로 동작)
 *  7. [ADR 0129 B] 자기억제(REENTRY_COOLDOWN·CODE_DEDUP) benign 제외 (owner 무관)
 *  8. [ADR 0129 C] owner=AUTOPILOT 한정 POSITION_LIMIT 제외 (공유풀 full=정상)
 *  9. [ADR 0129 C 격리 LOCK] owner=USER 는 POSITION_LIMIT 카운트 유지 (v3.14.0 거동)
 * 10. [회귀] owner=AUTOPILOT 도 실위험 룰(DAILY_LOSS 등)은 여전히 카운트
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
            riskEventRepository.countRecentBlockedByStrategy(strategyId = 100L, windowMinutes = 10L, excludedRules = any())
        } returns 5

        val result = guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `threshold 도달 시 true 반환 + onAutoDisable 호출`() {
        every {
            riskEventRepository.countRecentBlockedByStrategy(strategyId = 100L, windowMinutes = 10L, excludedRules = any())
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

    @Test
    fun `F9 — 무해 가드 rule 제외 set 을 repo 카운트에 전달한다`() {
        val excludedSlot = slot<Set<String>>()
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 100L,
                windowMinutes = 10L,
                excludedRules = capture(excludedSlot),
            )
        } returns 0

        guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 10)

        // 보유 0 SELL 차단·중복 매수 차단·익절 예약 SELL 가용 0 = 정상 거동 → 임계 제외 의무.
        // ADR 0129 레버5-B — autopilot 자기억제(churn cooldown · code dedup)도 benign 합류.
        // ADR 0130 R-aux — ADR 0129 진입 가드 3룰(LATE_ENTRY·OPEN_BLACKOUT·REENTRY_PRICE_GUARD)도 benign 합류.
        assertThat(excludedSlot.captured)
            .containsExactlyInAnyOrder(
                RiskRule.SELL_WITHOUT_POSITION.name,
                RiskRule.MAX_CONCURRENT_POSITIONS.name,
                RiskRule.SELL_QUANTITY_UNAVAILABLE.name,
                RiskRule.AUTOPILOT_REENTRY_COOLDOWN.name,
                RiskRule.AUTOPILOT_CODE_DEDUP.name,
                RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED.name,
                RiskRule.AUTOPILOT_OPEN_BLACKOUT.name,
                RiskRule.AUTOPILOT_REENTRY_PRICE_GUARD.name,
                // 종목당 1일 왕복 cap(2026-06-24) — churn 자기억제 benign 합류.
                RiskRule.AUTOPILOT_REENTRY_DAY_CAP.name,
            )
        // 진짜 자본 위험 신호는 제외하지 않는다(회귀 방지). USER 경로(ownerIsAutopilot=false default)이므로
        // POSITION_LIMIT 은 여전히 카운트(레버5-C 격리 LOCK).
        assertThat(excludedSlot.captured)
            .doesNotContain(
                RiskRule.DAILY_LOSS_LIMIT.name,
                RiskRule.POSITION_LIMIT.name,
                RiskRule.TRADE_FREQUENCY_MINUTE.name,
                RiskRule.KILL_SWITCH_ON.name,
            )
    }

    // ── ADR 0129 레버5-B — 자기억제 가드 benign 제외 (owner 무관) ──────────────────────────────────────

    @Test
    fun `B — AUTOPILOT_REENTRY_COOLDOWN·CODE_DEDUP 은 benign set 에 포함되어 카운트 제외된다`() {
        // 자기억제 룰은 autopilot 이 스스로 churn·중복을 억제하는 정상 거동 — 임계에 기여하면 안 된다.
        assertThat(BENIGN_AUTO_PAUSE_EXCLUDED_RULES)
            .contains(
                RiskRule.AUTOPILOT_REENTRY_COOLDOWN.name,
                RiskRule.AUTOPILOT_CODE_DEDUP.name,
            )
    }

    @Test
    fun `B 회귀 — 자기억제 룰만 누적된 전략은 제외 후 0 이면 정지 0 (owner 무관)`() {
        // repo 계약: benign 제외(자기억제 포함) 후 위험분 0 → flat autopilot 전략이 자기억제로 죽지 않는다.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 90L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 0

        // ownerIsAutopilot=false(USER 경로)에서도 benign set 적용 — 자기억제 룰은 owner 무관 전역 제외.
        val result = guard.checkAndPauseIfNeeded(strategyId = 90L, autoPauseThreshold = 5)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    // ── ADR 0130 R-aux — ADR 0129 진입 가드 3룰 benign 합류 (2026-06-23 14:00 라이브 회귀 fix) ──────────

    @Test
    fun `R-aux — 진입 가드 3룰(LATE_ENTRY·OPEN_BLACKOUT·REENTRY_PRICE_GUARD)은 benign set 에 포함된다`() {
        // 늦은 진입 차단·개장 blackout·재진입 추격가 차단은 autopilot 의 정상 진입 자기억제 — 임계에 기여하면 안 된다.
        assertThat(BENIGN_AUTO_PAUSE_EXCLUDED_RULES)
            .contains(
                RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED.name,
                RiskRule.AUTOPILOT_OPEN_BLACKOUT.name,
                RiskRule.AUTOPILOT_REENTRY_PRICE_GUARD.name,
            )
    }

    @Test
    fun `R-aux — AUTOPILOT set 에도 진입 가드 3룰이 전파된다(BENIGN 합집합)`() {
        // AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES = BENIGN ∪ POSITION_LIMIT 이므로 benign 합류분이 자동 전파(DRY).
        assertThat(AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES)
            .contains(
                RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED.name,
                RiskRule.AUTOPILOT_OPEN_BLACKOUT.name,
                RiskRule.AUTOPILOT_REENTRY_PRICE_GUARD.name,
            )
    }

    @Test
    fun `R-aux 회귀 — 진입 가드 3룰만으로 임계 도달해도 제외 후 0 이면 정지 0 (14시 LATE_ENTRY 회귀 해소)`() {
        // 2026-06-23 14:00 라이브 재현: LATE_ENTRY 등 진입 가드만 누적된 전략이 임계를 채워도 죽지 않아야 한다.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 103L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 0

        // owner 무관 전역 제외 — USER 경로(default)에서도 진입 가드 3룰은 제외된다.
        val result = guard.checkAndPauseIfNeeded(strategyId = 103L, autoPauseThreshold = 5)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    // ── ADR 0129 레버5-C — owner=AUTOPILOT 한정 POSITION_LIMIT 제외 (USER 격리 LOCK) ────────────────────

    @Test
    fun `C — ownerIsAutopilot=true 면 AUTOPILOT set(POSITION_LIMIT 포함)으로 카운트한다`() {
        val excludedSlot = slot<Set<String>>()
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 100L,
                windowMinutes = 10L,
                excludedRules = capture(excludedSlot),
            )
        } returns 0

        guard.checkAndPauseIfNeeded(strategyId = 100L, autoPauseThreshold = 5, ownerIsAutopilot = true)

        // 공유풀 full = 정상 → POSITION_LIMIT 추가 제외. 자기억제·benign 도 모두 포함(합집합).
        assertThat(excludedSlot.captured).isEqualTo(AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES)
        assertThat(excludedSlot.captured).contains(RiskRule.POSITION_LIMIT.name)
    }

    @Test
    fun `C — owner=AUTOPILOT + POSITION_LIMIT 10건이어도 제외 후 위험분 0 이면 정지 0 (개장 12분 5대6 회귀 해소)`() {
        // 2026-06-23 라이브 회귀 재현: 공유풀 full 로 POSITION_LIMIT 폭주해도 autopilot 은 정지하지 않는다.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 101L,
                windowMinutes = 10L,
                excludedRules = AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 0

        val result = guard.checkAndPauseIfNeeded(strategyId = 101L, autoPauseThreshold = 5, ownerIsAutopilot = true)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `C 격리 LOCK — owner=USER 는 POSITION_LIMIT 을 제외하지 않는다(현행 BENIGN set 그대로)`() {
        val excludedSlot = slot<Set<String>>()
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 200L,
                windowMinutes = 10L,
                excludedRules = capture(excludedSlot),
            )
        } returns 0

        // ownerIsAutopilot=false(default) — USER/SYSTEM 경로.
        guard.checkAndPauseIfNeeded(strategyId = 200L, autoPauseThreshold = 5)

        // USER 는 POSITION_LIMIT 이 실제 자본 위험 신호 — 절대 제외 안 함(격리 위반 방지).
        assertThat(excludedSlot.captured).isEqualTo(BENIGN_AUTO_PAUSE_EXCLUDED_RULES)
        assertThat(excludedSlot.captured).doesNotContain(RiskRule.POSITION_LIMIT.name)
    }

    @Test
    fun `C 격리 — owner=USER + POSITION_LIMIT 누적이 임계 도달이면 현행대로 정지 발동`() {
        // USER 격리: POSITION_LIMIT 은 제외되지 않으므로 카운트에 남아 v3.14.0 거동대로 자동정지.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 201L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 5

        val result = guard.checkAndPauseIfNeeded(strategyId = 201L, autoPauseThreshold = 5)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            callback.onAutoDisable(strategyId = 201L, reason = StrategyDisabledReason.RISK_VIOLATION)
        }
    }

    @Test
    fun `회귀 — owner=AUTOPILOT 도 실위험 룰(DAILY_LOSS 등)은 여전히 카운트되어 정지 발동`() {
        // AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES 는 DAILY_LOSS_LIMIT·TRADE_FREQUENCY·KILL_SWITCH 를 제외하지 않는다.
        assertThat(AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES)
            .doesNotContain(
                RiskRule.DAILY_LOSS_LIMIT.name,
                RiskRule.TRADE_FREQUENCY_MINUTE.name,
                RiskRule.TRADE_FREQUENCY_DAY.name,
                RiskRule.KILL_SWITCH_ON.name,
            )
        // 실위험 분이 임계 도달 → autopilot 도 정지(자본 안전 보존).
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 102L,
                windowMinutes = 10L,
                excludedRules = AUTOPILOT_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 5

        val result = guard.checkAndPauseIfNeeded(strategyId = 102L, autoPauseThreshold = 5, ownerIsAutopilot = true)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            callback.onAutoDisable(strategyId = 102L, reason = StrategyDisabledReason.RISK_VIOLATION)
        }
    }

    @Test
    fun `F9 회귀 — 무해 rule 10건이어도 제외 후 카운트 0이면 정지 0`() {
        // repo 계약: excludedRules 적용 후 카운트 반환 → 무해 가드만 누적된 flat 전략은 0 으로 떨어진다.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 85L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 0

        val result = guard.checkAndPauseIfNeeded(strategyId = 85L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }

    @Test
    fun `F9 회귀 — 위험 rule 10건이면 제외 후에도 임계 도달 → 정지 발동`() {
        // 위험 rule(DAILY_LOSS_LIMIT 등)은 제외 set 에 없으므로 카운트에 그대로 남아 임계 도달.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 86L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 10

        val result = guard.checkAndPauseIfNeeded(strategyId = 86L, autoPauseThreshold = 10)

        assertThat(result).isTrue()
        verify(exactly = 1) {
            callback.onAutoDisable(strategyId = 86L, reason = StrategyDisabledReason.RISK_VIOLATION)
        }
    }

    @Test
    fun `F9 회귀 — 혼합(무해+위험) 제외 후 위험분만 임계 미달이면 정지 0`() {
        // 전체 BLOCKED 12건이라도 무해 가드 제외 후 위험분 7건 < threshold 10 → 정지 안 함.
        every {
            riskEventRepository.countRecentBlockedByStrategy(
                strategyId = 87L,
                windowMinutes = 10L,
                excludedRules = BENIGN_AUTO_PAUSE_EXCLUDED_RULES,
            )
        } returns 7

        val result = guard.checkAndPauseIfNeeded(strategyId = 87L, autoPauseThreshold = 10)

        assertThat(result).isFalse()
        verify(exactly = 0) { callback.onAutoDisable(any(), any()) }
    }
}

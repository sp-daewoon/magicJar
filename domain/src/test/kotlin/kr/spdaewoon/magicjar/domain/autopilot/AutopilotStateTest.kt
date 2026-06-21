package kr.spdaewoon.magicjar.domain.autopilot

import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * [AutopilotState] 도메인 단위 테스트 — autopilot Phase 0 Task 1 (안전 하니스 기반).
 *
 * 핵심 계약 (spec 2026-06-22 §2-0-A · §3-bis):
 *  1. 보수적 default 한도 = 풀5% / 건당20% / 동시3 / 일일30% / 누적50% (코드 강제 단일 출처).
 *  2. autopilot 격리 owner = [StrategyOwner.AUTOPILOT] 존재(롤백·kill 의 식별자).
 *  3. 순수 도메인 — autopilot_state 컬럼을 평탄하게 미러링하는 라운드트립.
 */
class AutopilotStateTest {
    @Test
    fun `default 한도는 스펙 2-0-A 보수적 값과 일치한다`() {
        val state = AutopilotState()

        assertFalse(state.killActive, "kill switch 는 default off")
        assertEquals(0, BigDecimal("5").compareTo(state.poolPct), "풀 상한 = 순자산 5%")
        assertEquals(0, BigDecimal("20").compareTo(state.perTradePct), "건당 캡 = 풀 20%")
        assertEquals(3, state.maxPositions, "동시 보유 3")
        assertEquals(0, BigDecimal("30").compareTo(state.dailyLossPct), "일일 손실 30%")
        assertEquals(0, BigDecimal("50").compareTo(state.cumLossPct), "누적 손실 50%")
        assertEquals(0, BigDecimal.ZERO.compareTo(state.cumRealizedLoss), "누적 실현 손실 0")
        assertEquals(0, BigDecimal.ZERO.compareTo(state.dailyRealizedLoss), "당일 실현 손실 0")
    }

    @Test
    fun `companion default 상수가 인스턴스 default 와 동일하다`() {
        val state = AutopilotState()
        assertEquals(0, AutopilotState.DEFAULT_POOL_PCT.compareTo(state.poolPct))
        assertEquals(0, AutopilotState.DEFAULT_PER_TRADE_PCT.compareTo(state.perTradePct))
        assertEquals(AutopilotState.DEFAULT_MAX_POSITIONS, state.maxPositions)
        assertEquals(0, AutopilotState.DEFAULT_DAILY_LOSS_PCT.compareTo(state.dailyLossPct))
        assertEquals(0, AutopilotState.DEFAULT_CUM_LOSS_PCT.compareTo(state.cumLossPct))
    }

    @Test
    fun `isKillActive 는 killActive 를 그대로 반영한다`() {
        assertFalse(AutopilotState().isKillActive)
        assertTrue(AutopilotState(killActive = true).isKillActive)
    }

    @Test
    fun `autopilot_state 컬럼을 평탄하게 미러링하는 라운드트립`() {
        val today = LocalDate.of(2026, 6, 22)
        val state =
            AutopilotState(
                killActive = true,
                poolPct = BigDecimal("4.5"),
                perTradePct = BigDecimal("15"),
                maxPositions = 2,
                dailyLossPct = BigDecimal("25"),
                cumLossPct = BigDecimal("40"),
                cumRealizedLoss = BigDecimal("123456.7800"),
                dailyRealizedLoss = BigDecimal("1000.0000"),
                lastResetDate = today,
            )

        assertTrue(state.killActive)
        assertEquals(0, BigDecimal("4.5").compareTo(state.poolPct))
        assertEquals(0, BigDecimal("15").compareTo(state.perTradePct))
        assertEquals(2, state.maxPositions)
        assertEquals(0, BigDecimal("25").compareTo(state.dailyLossPct))
        assertEquals(0, BigDecimal("40").compareTo(state.cumLossPct))
        assertEquals(0, BigDecimal("123456.78").compareTo(state.cumRealizedLoss))
        assertEquals(0, BigDecimal("1000").compareTo(state.dailyRealizedLoss))
        assertEquals(today, state.lastResetDate)

        // data class copy 보존(영속 어댑터가 부분 갱신할 때 안전)
        val killed = state.copy(killActive = false)
        assertFalse(killed.killActive)
        assertEquals(state.poolPct, killed.poolPct)
    }

    @Test
    fun `StrategyOwner 에 AUTOPILOT 격리 식별자가 존재한다`() {
        assertTrue(StrategyOwner.entries.contains(StrategyOwner.AUTOPILOT))
        // 기존 SYSTEM·USER 보존(zero-regression)
        assertTrue(StrategyOwner.entries.contains(StrategyOwner.SYSTEM))
        assertTrue(StrategyOwner.entries.contains(StrategyOwner.USER))
        assertEquals("AUTOPILOT", StrategyOwner.AUTOPILOT.name)
    }
}

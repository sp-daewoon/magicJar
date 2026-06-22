package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [AutopilotPaperShadowRecorder] starve 섀도 기록 게이트 단위 테스트 (개선 G).
 *
 * 핵심 불변식:
 *  - enabled=false → no-op(zero-regression).
 *  - non-BUY·non-AUTOPILOT·non-SHADOWABLE rule → no-op.
 *  - 슬롯/풀 starve(MAX_CONCURRENT_POSITIONS·POSITION_LIMIT) + owner=AUTOPILOT BUY → 섀도 1행 기록.
 *  - 동일 (strategyId,code) OPEN 섀도 존재 → 중복 skip.
 *  - 기록 실패(DB 예외) → fail-open(예외 전파 0).
 */
class AutopilotPaperShadowRecorderTest {
    private val repo = mockk<AutopilotPaperShadowJpaRepository>(relaxed = true)

    private fun recorder(enabled: Boolean): AutopilotPaperShadowRecorder =
        AutopilotPaperShadowRecorder(
            properties = MagicjarAutopilotProperties(enabled = enabled),
            shadowRepository = repo,
            virtualNotionalKrw = BigDecimal("1000000"),
        )

    @Test
    fun `enabled=false 면 no-op (zero-regression)`() {
        recorder(enabled = false).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `slot starve + owner=AUTOPILOT BUY 면 섀도 1행 기록`() {
        every { repo.countOpenByStrategyAndCode(any(), any()) } returns 0L
        val saved = slot<AutopilotPaperShadowJpaEntity>()
        every { repo.save(capture(saved)) } answers { saved.captured }

        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )

        verify(exactly = 1) { repo.save(any()) }
        // 가상 수량 = 1,000,000 / 70,000 = 14.
        assertThat(saved.captured.quantity).isEqualTo(14)
        assertThat(saved.captured.status).isEqualTo(AutopilotPaperShadowJpaEntity.STATUS_OPEN)
        assertThat(saved.captured.blockReason).isEqualTo("MAX_CONCURRENT_POSITIONS")
        assertThat(saved.captured.takeProfitPct).isEqualByComparingTo("2.50")
        assertThat(saved.captured.stopLossPct).isEqualByComparingTo("2.00")
        assertThat(saved.captured.interval).isEqualTo("5m")
    }

    @Test
    fun `POSITION_LIMIT 도 SHADOWABLE`() {
        every { repo.countOpenByStrategyAndCode(any(), any()) } returns 0L
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.POSITION_LIMIT,
        )
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `non-SHADOWABLE rule(KILL_SWITCH_ON·dedup) 은 기록 안 함`() {
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.KILL_SWITCH_ON,
        )
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.AUTOPILOT_CODE_DEDUP,
        )
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `non-AUTOPILOT 또는 SELL 은 기록 안 함`() {
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.USER),
            ownerIsAutopilot = false,
            strategy = strategy(owner = StrategyOwner.USER),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT).copy(side = SignalSide.SELL),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `TP·SL 미상 전략은 점수 산정 불가 — skip`() {
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(tp = null, sl = null),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `동일 (strategyId,code) OPEN 섀도 존재하면 중복 skip`() {
        every { repo.countOpenByStrategyAndCode(any(), any()) } returns 1L
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `기록 실패(DB 예외)는 fail-open — 예외 전파 0`() {
        every { repo.countOpenByStrategyAndCode(any(), any()) } returns 0L
        every { repo.save(any()) } throws RuntimeException("db down")
        // 예외 던지지 않아야 한다(실 매매 경로 보호).
        recorder(enabled = true).recordIfStarved(
            buySignal(StrategyOwner.AUTOPILOT),
            ownerIsAutopilot = true,
            strategy = strategy(),
            blockReason = RiskRule.MAX_CONCURRENT_POSITIONS,
        )
    }

    // ---- fixtures ----

    private fun buySignal(owner: StrategyOwner): Signal =
        Signal(
            id = 1L,
            strategyId = 100L,
            userId = "user-A",
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = Clock.System.now(),
            side = SignalSide.BUY,
            strength = 80,
            reason = "test",
            priceAtSignal = BigDecimal("70000"),
            strategyOwner = owner,
        )

    private fun strategy(
        owner: StrategyOwner = StrategyOwner.AUTOPILOT,
        tp: BigDecimal? = BigDecimal("2.50"),
        sl: BigDecimal? = BigDecimal("2.00"),
    ): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            owner = owner,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "autopilot-fvg", buyCondition = null),
                    fixedStopLoss = sl,
                    takeProfitPct = tp,
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 1,
                    indicatorCallCount = 0,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
            intervals = listOf("5m"),
            codes = listOf("005930"),
        )
}

package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [AutopilotLossAccumulator] 손실 누적 hook 단위 테스트 (spec 2026-06-22 §2-0-A · Phase 0 Task 3).
 *
 * 검증: 게이트 off no-op / owner=AUTOPILOT 손실만 누적(양수 절대값) / 이익·무손실 no-op / owner≠AUTOPILOT no-op / sentinel no-op.
 */
class AutopilotLossAccumulatorTest {
    private val stateStore = mockk<AutopilotStateStore>(relaxed = true)
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>()

    private fun accumulator(enabled: Boolean): AutopilotLossAccumulator =
        AutopilotLossAccumulator(
            properties = MagicjarAutopilotProperties(enabled = enabled),
            stateStore = stateStore,
            strategyRepository = strategyRepo,
        )

    @Test
    fun `게이트 off 면 no-op (zero-regression)`() {
        accumulator(enabled = false).onSellRealized(100L, BigDecimal("-50000"))
        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
        verify(exactly = 0) { stateStore.accumulateRealizedLoss(any()) }
    }

    @Test
    fun `owner=AUTOPILOT 손실(음수) 이면 절대값을 누적`() {
        every { strategyRepo.countAutopilotStrategyById(100L) } returns 1L
        every { stateStore.accumulateRealizedLoss(any()) } returns AutopilotState(cumRealizedLoss = BigDecimal("50000"))

        accumulator(enabled = true).onSellRealized(100L, BigDecimal("-50000"))

        verify(exactly = 1) { stateStore.accumulateRealizedLoss(BigDecimal("50000.0000")) }
    }

    @Test
    fun `이익(양수) 이면 누적 0 (손실만 카운트)`() {
        accumulator(enabled = true).onSellRealized(100L, BigDecimal("30000"))
        verify(exactly = 0) { stateStore.accumulateRealizedLoss(any()) }
        // owner 조회조차 안 함 — 양수에서 조기 반환.
        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
    }

    @Test
    fun `owner != AUTOPILOT 이면 누적 0 (사용자 전략 무영향)`() {
        every { strategyRepo.countAutopilotStrategyById(200L) } returns 0L

        accumulator(enabled = true).onSellRealized(200L, BigDecimal("-50000"))

        verify(exactly = 0) { stateStore.accumulateRealizedLoss(any()) }
    }

    @Test
    fun `MANUAL sentinel(0) 이면 no-op`() {
        accumulator(enabled = true).onSellRealized(0L, BigDecimal("-50000"))
        verify(exactly = 0) { strategyRepo.countAutopilotStrategyById(any()) }
    }
}

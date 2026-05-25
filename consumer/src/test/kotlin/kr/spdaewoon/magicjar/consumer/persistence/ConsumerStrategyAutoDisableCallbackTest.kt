package kr.spdaewoon.magicjar.consumer.persistence

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import org.junit.jupiter.api.Test

class ConsumerStrategyAutoDisableCallbackTest {
    private val strategyJpaRepository = mockk<ConsumerStrategyJpaRepository>()
    private val callback = ConsumerStrategyAutoDisableCallback(strategyJpaRepository)

    @Test
    fun `CPU budget auto-disable updates consumer strategy table`() {
        every { strategyJpaRepository.autoDisable(42L, "CPU_BUDGET_EXCEEDED") } returns 1

        callback.onAutoDisable(42L, StrategyDisabledReason.CPU_BUDGET_EXCEEDED)

        verify(exactly = 1) { strategyJpaRepository.autoDisable(42L, "CPU_BUDGET_EXCEEDED") }
    }
}

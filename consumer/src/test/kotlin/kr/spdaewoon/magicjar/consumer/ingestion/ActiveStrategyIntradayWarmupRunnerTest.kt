package kr.spdaewoon.magicjar.consumer.ingestion

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.reconciliation.SymbolDetailMinuteGapFillService
import org.junit.jupiter.api.Test

/**
 * follow-up J — [ActiveStrategyIntradayWarmupRunner]가 개장 직후 ACTIVE 전략 종목의 인트라데이 캔들
 * backfill 을 검증된 on-demand 경로([SymbolDetailMinuteGapFillService.submitIfNeeded])로 트리거하는지 검증.
 *
 * 부팅 catch-up·cron(09:01) 양쪽이 같은 [warmupActiveStrategies]를 거치므로, cron 진입점([onMarketOpen])으로
 * 핵심 동작(전략 종목 each 큐잉 / 빈 종목 / disabled / 조회 실패 graceful)을 본다.
 */
class ActiveStrategyIntradayWarmupRunnerTest {
    private val codeProvider = mockk<ActiveStrategyCodeProvider>()
    private val gapFill = mockk<SymbolDetailMinuteGapFillService>()

    private fun runner(enabled: Boolean = true) =
        ActiveStrategyIntradayWarmupRunner(
            activeStrategyCodeProvider = codeProvider,
            minuteGapFillService = gapFill,
            enabled = enabled,
        )

    @Test
    fun `개장 직후 ACTIVE 전략 종목 each 에 인트라데이 gap-fill 을 큐잉한다`() {
        every { codeProvider.activeCodes() } returns setOf("093370", "005930")
        every { gapFill.submitIfNeeded(any(), any()) } just Runs

        runner().onMarketOpen()

        verify { gapFill.submitIfNeeded(code = "093370", reason = "intraday-warmup-market-open") }
        verify { gapFill.submitIfNeeded(code = "005930", reason = "intraday-warmup-market-open") }
    }

    @Test
    fun `ACTIVE 전략 종목이 없으면 gap-fill 호출 0`() {
        every { codeProvider.activeCodes() } returns emptySet()

        runner().onMarketOpen()

        verify(exactly = 0) { gapFill.submitIfNeeded(any(), any()) }
    }

    @Test
    fun `disabled 면 종목 조회조차 하지 않는다`() {
        runner(enabled = false).onMarketOpen()

        verify(exactly = 0) { codeProvider.activeCodes() }
        verify(exactly = 0) { gapFill.submitIfNeeded(any(), any()) }
    }

    @Test
    fun `전략 종목 조회 실패는 graceful — warmup 생략하고 예외 전파 안 함`() {
        every { codeProvider.activeCodes() } throws RuntimeException("DB down")

        runner().onMarketOpen()

        verify(exactly = 0) { gapFill.submitIfNeeded(any(), any()) }
    }
}

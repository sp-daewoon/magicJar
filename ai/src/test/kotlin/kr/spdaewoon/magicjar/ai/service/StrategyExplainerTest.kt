package kr.spdaewoon.magicjar.ai.service

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kr.spdaewoon.magicjar.ai.prompt.PromptLoader
import kr.spdaewoon.magicjar.core.domain.BacktestResult
import kr.spdaewoon.magicjar.core.domain.Metrics
import kr.spdaewoon.magicjar.core.domain.StrategyRef
import kr.spdaewoon.magicjar.core.port.AiClient
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

class StrategyExplainerTest {
    private val promptLoader =
        mockk<PromptLoader> {
            every { load(any()) } returns "SYSTEM"
        }
    private val aiClient = mockk<AiClient>()
    private val explainer = StrategyExplainer(aiClient, promptLoader)

    @Test
    fun `정상 응답은 trim 된 문자열 Optional`() {
        every { aiClient.chat(any(), any()) } returns "  ## 요약\n좋은 전략.  \n"
        val result = explainer.explain(sample()).get()
        result shouldContain "요약"
    }

    @Test
    fun `유저 메시지에 지표가 포함된다`() {
        val userSlot = slot<String>()
        every { aiClient.chat(any(), capture(userSlot)) } returns "ok"
        explainer.explain(sample(), riskTolerance = "AGGRESSIVE", preferredSectors = listOf("반도체"))
        userSlot.captured shouldContain "CAGR"
        userSlot.captured shouldContain "AGGRESSIVE"
        userSlot.captured shouldContain "반도체"
    }

    @Test
    fun `null 응답은 empty`() {
        every { aiClient.chat(any(), any()) } returns null
        explainer.explain(sample()).isPresent shouldBe false
    }

    private fun sample(): BacktestResult =
        BacktestResult(
            strategy = StrategyRef("rsi-mean-reversion", 1),
            from = LocalDate.of(2025, 1, 1),
            to = LocalDate.of(2025, 12, 31),
            metrics =
                Metrics(
                    totalReturn = BigDecimal("0.18"),
                    cagr = BigDecimal("0.18"),
                    mdd = BigDecimal("-0.12"),
                    sharpe = BigDecimal("1.3"),
                    winRate = BigDecimal("0.55"),
                    tradeCount = 42,
                ),
            trades = emptyList(),
        )
}

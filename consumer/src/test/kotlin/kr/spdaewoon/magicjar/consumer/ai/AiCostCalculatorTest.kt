package kr.spdaewoon.magicjar.consumer.ai

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * consumer 측 AiCostCalculator — api 측 AiCostTracker.computeCost() 와 동일 표 LOCK 검증.
 */
class AiCostCalculatorTest {
    private val calc = AiCostCalculator()

    @Test
    fun `mock provider - cost 0`() {
        calc.compute("mock", "mock-1.0", 100, 200) shouldBe BigDecimal.ZERO
    }

    @Test
    fun `anthropic haiku 1000 1000 = 0_0048`() {
        calc.compute("anthropic", "claude-3-5-haiku-20241022", 1000, 1000) shouldBe BigDecimal("0.004800")
    }

    @Test
    fun `gemini flash 0 tokens - cost 0`() {
        calc.compute("gemini", "gemini-1.5-flash", 0, 0) shouldBe BigDecimal("0.000000")
    }

    @Test
    fun `openai gpt-4o-mini 1000 1000 = 0_00075`() {
        calc.compute("openai", "gpt-4o-mini", 1000, 1000) shouldBe BigDecimal("0.000750")
    }
}

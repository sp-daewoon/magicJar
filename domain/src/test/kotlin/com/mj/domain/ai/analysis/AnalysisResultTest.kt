package com.mj.domain.ai.analysis

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class AnalysisResultTest {
    private fun sample(
        text: String = "본 종목은 단기 추세가 우상향 흐름입니다.",
        confidence: Double = 0.7,
    ) = AnalysisResult(
        text = text,
        confidence = confidence,
        model = "claude-sonnet-4-5",
        promptVersion = "v1",
        tokensUsed = AnalysisResult.TokensUsed(input = 120, output = 80),
        costEstimate = 0.0042,
        durationMs = 850,
    )

    @Test
    fun `confidence out of range throws`() {
        shouldThrow<IllegalArgumentException> { sample(confidence = -0.01) }
        shouldThrow<IllegalArgumentException> { sample(confidence = 1.01) }
    }

    @Test
    fun `text must not be blank`() {
        shouldThrow<IllegalArgumentException> { sample(text = "   ") }
    }

    @Test
    fun `tokens total sums input and output`() {
        sample().tokensUsed.total shouldBe 200
    }

    @Test
    fun `summary truncates long text with ellipsis`() {
        val long = "가".repeat(200)
        val r = sample(text = long)
        val s = r.summary(maxChars = 80)
        s.endsWith("...") shouldBe true
        s.length shouldBe 83
    }

    @Test
    fun `summary leaves short text unchanged without ellipsis`() {
        val r = sample(text = "짧은 코멘트")
        r.summary().endsWith("...") shouldBe false
    }
}

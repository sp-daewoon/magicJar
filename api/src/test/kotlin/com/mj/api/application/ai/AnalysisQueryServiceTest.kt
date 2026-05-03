package com.mj.api.application.ai

import com.mj.api.infrastructure.ai.AnalysisAiProperties
import com.mj.api.infrastructure.persistence.ai.AiAnalysisLogEntity
import com.mj.api.infrastructure.persistence.ai.AiAnalysisLogJpaRepository
import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import com.mj.domain.ai.analysis.port.AnalysisAssistant
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

class AnalysisQueryServiceTest {
    private val repo = mockk<AiAnalysisLogJpaRepository>(relaxed = true)
    private val assistant = mockk<AnalysisAssistant>()
    private val provider = mockk<ObjectProvider<AnalysisAssistant>>()
    private val props = AnalysisAiProperties(enabled = true)

    init {
        every { provider.getIfAvailable() } returns assistant
        every { assistant.providerName } returns "anthropic"
    }

    private val sut = AnalysisQueryService(provider, repo, props)

    private fun symbolReq() =
        AnalysisRequest.SymbolCommentary(
            userId = 1L,
            code = "005930",
            contextSnapshot =
                AnalysisRequest.SymbolContextSnapshot(
                    nameKr = "삼성전자",
                    sector = "반도체",
                    lastClose = 72_500.0,
                ),
        )

    private fun successResult() =
        AnalysisResult(
            text = "분석 결과 [면책] 본 분석은 AI 옵션 보조용...",
            confidence = 0.6,
            model = "claude-sonnet-4-5",
            promptVersion = "v1",
            tokensUsed = AnalysisResult.TokensUsed(input = 100, output = 50),
            costEstimate = 0.001,
            durationMs = 600,
        )

    @Test
    fun `success path persists audit row with success=true`() {
        val captured = slot<AiAnalysisLogEntity>()
        every { repo.save(capture(captured)) } answers { firstArg() }
        every { assistant.analyze(any()) } returns Result.success(successResult())

        val result = sut.analyze(symbolReq())

        result.text shouldBe "분석 결과 [면책] 본 분석은 AI 옵션 보조용..."
        captured.captured.success shouldBe true
        captured.captured.requestType shouldBe "SYMBOL_COMMENTARY"
        captured.captured.targetRef shouldBe "005930"
        captured.captured.tokensInput shouldBe 100
        captured.captured.tokensOutput shouldBe 50
        captured.captured.provider shouldBe "anthropic"
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `failure path persists audit with success=false and throws AnalysisUnavailableException`() {
        val captured = slot<AiAnalysisLogEntity>()
        every { repo.save(capture(captured)) } answers { firstArg() }
        every { assistant.analyze(any()) } returns Result.failure(RuntimeException("upstream timeout"))

        shouldThrow<AnalysisUnavailableException> {
            sut.analyze(symbolReq())
        }
        captured.captured.success shouldBe false
        captured.captured.errorMessage shouldBe "upstream timeout"
        captured.captured.tokensInput shouldBe 0
        captured.captured.tokensOutput shouldBe 0
        verify(exactly = 1) { repo.save(any()) }
    }

    @Test
    fun `assistant absent throws AnalysisUnavailableException without saving`() {
        every { provider.getIfAvailable() } returns null

        shouldThrow<AnalysisUnavailableException> {
            sut.analyze(symbolReq())
        }
        verify(exactly = 0) { repo.save(any()) }
    }

    @Test
    fun `targetRef encodes interval for chart interpretation`() {
        val captured = slot<AiAnalysisLogEntity>()
        every { repo.save(capture(captured)) } answers { firstArg() }
        every { assistant.analyze(any()) } returns Result.success(successResult())

        sut.analyze(
            AnalysisRequest.ChartInterpretation(
                userId = 1L,
                code = "005930",
                interval = "1m",
                candles = emptyList(),
            ),
        )
        captured.captured.targetRef shouldBe "005930@1m"
        captured.captured.requestType shouldBe "CHART_INTERPRETATION"
    }

    @Test
    fun `targetRef uses tradeId for journal retrospective`() {
        val captured = slot<AiAnalysisLogEntity>()
        every { repo.save(capture(captured)) } answers { firstArg() }
        every { assistant.analyze(any()) } returns Result.success(successResult())

        sut.analyze(
            AnalysisRequest.JournalRetrospective(
                userId = 1L,
                tradeId = 99L,
                journalEntry =
                    AnalysisRequest.JournalSnapshot(
                        symbol = "005930",
                        side = "BUY",
                        entryPrice = 70_000.0,
                        exitPrice = null,
                        pnlPct = null,
                        rationale = null,
                        openedAt = java.time.Instant.now(),
                        closedAt = null,
                    ),
            ),
        )
        captured.captured.targetRef shouldBe "99"
        captured.captured.requestType shouldBe "JOURNAL_RETROSPECTIVE"
    }

    @Test
    fun `inputSummary truncated to 500 chars`() {
        val captured = slot<AiAnalysisLogEntity>()
        every { repo.save(capture(captured)) } answers { firstArg() }
        every { assistant.analyze(any()) } returns Result.success(successResult())

        // Long news headlines to force long summary
        val longNews = (1..200).map { "헤드라인_$it" }
        sut.analyze(
            AnalysisRequest.SymbolCommentary(
                userId = 1L,
                code = "005930",
                contextSnapshot = AnalysisRequest.SymbolContextSnapshot(recentNewsHeadlines = longNews),
            ),
        )
        (captured.captured.inputSummary.length <= 500) shouldBe true
    }
}

package com.mj.api.infrastructure.ai

import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class MockAnalysisAssistantTest {
    private val mock = MockAnalysisAssistant()

    @Test
    fun `providerName is mock`() {
        mock.providerName shouldBe "mock"
    }

    @Test
    fun `analyze returns success with disclaimer`() {
        val req =
            AnalysisRequest.SymbolCommentary(
                userId = 1L,
                code = "005930",
                contextSnapshot = AnalysisRequest.SymbolContextSnapshot(),
            )
        val result = mock.analyze(req).getOrThrow()
        result.text shouldContain "[면책]"
        result.text shouldContain "분석 옵션이 비활성된 상태"
        result.model shouldBe "mock"
        result.tokensUsed.total shouldBe 0
        result.costEstimate shouldBe 0.0
    }

    @Test
    fun `each request type produces a labeled mock response`() {
        val symbol =
            mock
                .analyze(
                    AnalysisRequest.SymbolCommentary(
                        userId = 1L,
                        code = "005930",
                        contextSnapshot = AnalysisRequest.SymbolContextSnapshot(),
                    ),
                ).getOrThrow()
        symbol.text shouldContain "종목 코멘트"

        val company =
            mock
                .analyze(
                    AnalysisRequest.CompanyAnalysis(userId = 1L, code = "005930"),
                ).getOrThrow()
        company.text shouldContain "기업 분석"

        val chart =
            mock
                .analyze(
                    AnalysisRequest.ChartInterpretation(userId = 1L, code = "005930", interval = "DAY", candles = emptyList()),
                ).getOrThrow()
        chart.text shouldContain "차트 해석"

        val indicator =
            mock
                .analyze(
                    AnalysisRequest.IndicatorMeaning(userId = 1L, indicatorId = "RSI"),
                ).getOrThrow()
        indicator.text shouldContain "지표 의미"

        val journal =
            mock
                .analyze(
                    AnalysisRequest.JournalRetrospective(
                        userId = 1L,
                        tradeId = 42L,
                        journalEntry =
                            AnalysisRequest.JournalSnapshot(
                                symbol = "005930",
                                side = "BUY",
                                entryPrice = 70_000.0,
                                exitPrice = 72_500.0,
                                pnlPct = 3.57,
                                rationale = "test",
                                openedAt = java.time.Instant.now(),
                                closedAt = java.time.Instant.now(),
                            ),
                    ),
                ).getOrThrow()
        journal.text shouldContain "매매 복기"
    }

    @Test
    fun `disclaimer is the canonical constant`() {
        val req = AnalysisRequest.IndicatorMeaning(userId = 1L, indicatorId = "RSI")
        val result = mock.analyze(req).getOrThrow()
        result.text shouldContain AnalysisResult.DISCLAIMER
    }
}

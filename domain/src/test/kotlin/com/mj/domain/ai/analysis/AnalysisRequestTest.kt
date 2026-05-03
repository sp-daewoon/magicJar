package com.mj.domain.ai.analysis

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.time.Instant

class AnalysisRequestTest {
    @Test
    fun `sealed subclass discriminates 5 types`() {
        val now = Instant.now()
        val sym: AnalysisRequest =
            AnalysisRequest.SymbolCommentary(
                userId = 1L,
                code = "005930",
                contextSnapshot = AnalysisRequest.SymbolContextSnapshot(),
                requestedAt = now,
            )
        sym.shouldBeInstanceOf<AnalysisRequest.SymbolCommentary>()

        val co: AnalysisRequest = AnalysisRequest.CompanyAnalysis(userId = 1L, code = "005930")
        co.shouldBeInstanceOf<AnalysisRequest.CompanyAnalysis>()

        val ch: AnalysisRequest =
            AnalysisRequest.ChartInterpretation(
                userId = 1L,
                code = "005930",
                interval = "DAY",
                candles = emptyList(),
            )
        ch.shouldBeInstanceOf<AnalysisRequest.ChartInterpretation>()

        val ind: AnalysisRequest = AnalysisRequest.IndicatorMeaning(userId = 1L, indicatorId = "RSI14")
        ind.shouldBeInstanceOf<AnalysisRequest.IndicatorMeaning>()

        val journal: AnalysisRequest =
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
                        rationale = "5/20 골든크로스",
                        openedAt = now,
                        closedAt = now,
                    ),
            )
        journal.shouldBeInstanceOf<AnalysisRequest.JournalRetrospective>()
    }

    @Test
    fun `userId field exposed via base class`() {
        val sym: AnalysisRequest =
            AnalysisRequest.SymbolCommentary(
                userId = 99L,
                code = "005930",
                contextSnapshot = AnalysisRequest.SymbolContextSnapshot(),
            )
        sym.userId shouldBe 99L
    }
}

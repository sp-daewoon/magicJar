package com.mj.api.infrastructure.ai

import com.mj.domain.ai.analysis.AnalysisRequest
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import org.junit.jupiter.api.Test
import java.time.Instant

class AnalysisPromptBuilderTest {
    private val loader = AnalysisPromptLoader()
    private val builder = AnalysisPromptBuilder(loader)

    @Test
    fun `system base contains korean and disclaimer enforcement`() {
        val (system, _) =
            builder.build(
                AnalysisRequest.IndicatorMeaning(userId = 1L, indicatorId = "RSI"),
            )
        system shouldContain "한국어"
        system shouldContain "면책"
        system shouldContain "매매"
    }

    @Test
    fun `symbol commentary fills variables`() {
        val req =
            AnalysisRequest.SymbolCommentary(
                userId = 1L,
                code = "005930",
                contextSnapshot =
                    AnalysisRequest.SymbolContextSnapshot(
                        nameKr = "삼성전자",
                        sector = "반도체",
                        lastClose = 72500.0,
                        change1d = 1.2,
                        change5d = 3.4,
                        patterns = listOf("GOLDEN_CROSS"),
                        recentNewsHeadlines = listOf("반도체 호조"),
                    ),
            )
        val (_, user) = builder.build(req)
        user shouldContain "005930"
        user shouldContain "삼성전자"
        user shouldContain "반도체"
        user shouldContain "72500"
        user shouldContain "GOLDEN_CROSS"
        user shouldContain "반도체 호조"
        // 미치환 토큰 누수 체크
        user shouldNotContain "{{code}}"
        user shouldNotContain "{{nameKr}}"
    }

    @Test
    fun `symbol commentary with missing fields falls back to data missing`() {
        val req =
            AnalysisRequest.SymbolCommentary(
                userId = 1L,
                code = "005930",
                contextSnapshot = AnalysisRequest.SymbolContextSnapshot(),
            )
        val (_, user) = builder.build(req)
        user shouldContain "(미상)"
        user shouldContain "(데이터 부족)"
        user shouldContain "(없음)" // patterns
        user shouldContain "(최근 헤드라인 없음)"
    }

    @Test
    fun `company analysis fills numeric fields`() {
        val req =
            AnalysisRequest.CompanyAnalysis(
                userId = 1L,
                code = "005930",
                financialsHint =
                    AnalysisRequest.FinancialsHint(per = 12.4, pbr = 1.1, roe = 0.14, marketCap = 430_000_000_000_000L),
            )
        val (_, user) = builder.build(req)
        user shouldContain "12.4"
        user shouldContain "1.1"
        user shouldContain "0.14"
        user shouldContain "430000000000000"
    }

    @Test
    fun `chart interpretation truncates to MAX_CANDLES`() {
        val candles =
            (1..50).map {
                AnalysisRequest.CandleSnapshot(
                    time = "2026-04-${it.toString().padStart(2, '0')}",
                    open = 70000.0,
                    high = 71000.0,
                    low = 69500.0,
                    close = 70500.0,
                    volume = 1_000_000,
                )
            }
        val req =
            AnalysisRequest.ChartInterpretation(
                userId = 1L,
                code = "005930",
                interval = "DAY",
                candles = candles,
                indicators = mapOf("RSI14" to 65.2, "MACD" to 0.13),
            )
        val (_, user) = builder.build(req)
        user shouldContain "DAY"
        user shouldContain "RSI14"
        user shouldContain "MACD"
        // takeLast(30): 21~50번
        user shouldContain "2026-04-21"
        user shouldContain "2026-04-50"
        user shouldNotContain "2026-04-20"
    }

    @Test
    fun `indicator meaning shows default when params empty`() {
        val req = AnalysisRequest.IndicatorMeaning(userId = 1L, indicatorId = "RSI")
        val (_, user) = builder.build(req)
        user shouldContain "RSI"
        user shouldContain "(기본값)"
    }

    @Test
    fun `journal retrospective fills journal snapshot fields`() {
        val now = Instant.parse("2026-04-25T09:00:00Z")
        val req =
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
                        closedAt = now.plusSeconds(86_400),
                    ),
            )
        val (_, user) = builder.build(req)
        user shouldContain "005930"
        user shouldContain "BUY"
        user shouldContain "70000"
        user shouldContain "72500"
        user shouldContain "3.57"
        user shouldContain "5/20 골든크로스"
    }

    @Test
    fun `prompt version constant exposed`() {
        AnalysisPromptLoader.PROMPT_VERSION shouldBe "v1"
    }
}

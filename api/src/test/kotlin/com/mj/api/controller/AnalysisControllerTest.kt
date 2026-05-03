package com.mj.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.application.ai.AnalysisQueryService
import com.mj.api.application.ai.AnalysisUnavailableException
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import com.mj.domain.ai.analysis.port.AnalysisAssistant
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@WebMvcTest(
    controllers = [AnalysisController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class AnalysisControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun analysisQueryService(): AnalysisQueryService = mockk()

        /** 일반 빈으로 등록 — Spring이 자동으로 ObjectProvider<AnalysisAssistant>를 controller에 주입한다. */
        @Bean
        fun analysisAssistant(): AnalysisAssistant {
            val a = mockk<AnalysisAssistant>()
            every { a.providerName } returns "anthropic"
            return a
        }
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var service: AnalysisQueryService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private fun sampleResult(text: String = "본 종목은 단기 추세가 우상향 흐름입니다. [면책] 본 분석은 AI 옵션 보조용이며 매매 결정·추천이 아닙니다. 투자 책임은 사용자에게 있습니다.") =
        AnalysisResult(
            text = text,
            confidence = 0.6,
            model = "claude-sonnet-4-5",
            promptVersion = "v1",
            tokensUsed = AnalysisResult.TokensUsed(input = 120, output = 80),
            costEstimate = 0.0042,
            durationMs = 850,
        )

    @Test
    fun `POST symbol commentary — 200 with response`() {
        every { service.analyze(any<AnalysisRequest.SymbolCommentary>()) } returns sampleResult()

        val body =
            """
            {
              "userId": 1,
              "nameKr": "삼성전자",
              "sector": "반도체",
              "lastClose": 72500.0,
              "change1d": 1.2,
              "change5d": 3.4,
              "patterns": ["GOLDEN_CROSS"],
              "recentNewsHeadlines": ["반도체 호조"]
            }
            """.trimIndent()

        mvc
            .post("/api/analysis/symbol/005930/commentary") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.text") { exists() }
                jsonPath("$.model") { value("claude-sonnet-4-5") }
                jsonPath("$.tokensUsed.total") { value(200) }
                jsonPath("$.provider") { value("anthropic") }
            }
    }

    @Test
    fun `POST company analysis — 200`() {
        every { service.analyze(any<AnalysisRequest.CompanyAnalysis>()) } returns sampleResult()

        val body =
            """
            {"userId": 1, "per": 12.4, "pbr": 1.1, "roe": 0.14, "marketCap": 430000000000000}
            """.trimIndent()

        mvc
            .post("/api/analysis/symbol/005930/company") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.text") { exists() }
            }
    }

    @Test
    fun `POST chart interpretation — 200`() {
        every { service.analyze(any<AnalysisRequest.ChartInterpretation>()) } returns sampleResult()

        val body =
            """
            {
              "userId": 1,
              "interval": "DAY",
              "candles": [
                {"time":"2026-04-30","open":72000,"high":73500,"low":71800,"close":72500,"volume":12345678}
              ],
              "indicators": {"RSI14": 65.2}
            }
            """.trimIndent()

        mvc
            .post("/api/analysis/symbol/005930/chart") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `POST indicator meaning — 200`() {
        every { service.analyze(any<AnalysisRequest.IndicatorMeaning>()) } returns sampleResult()

        val body = """{"userId": 1, "params": {"length": "14"}}"""

        mvc
            .post("/api/analysis/indicator/RSI/explain") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `POST journal retrospective — 200`() {
        every { service.analyze(any<AnalysisRequest.JournalRetrospective>()) } returns sampleResult()

        val body =
            """
            {
              "userId": 1,
              "symbol": "005930",
              "side": "BUY",
              "entryPrice": 70000.0,
              "exitPrice": 72500.0,
              "pnlPct": 3.57,
              "rationale": "5/20 골든크로스",
              "openedAt": "2026-04-25T09:00:00Z",
              "closedAt": "2026-04-30T14:30:00Z"
            }
            """.trimIndent()

        mvc
            .post("/api/analysis/journal/42/retrospective") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `503 when AnalysisUnavailableException thrown by service`() {
        every { service.analyze(any()) } throws
            AnalysisUnavailableException("AI 분석 보조가 비활성 상태입니다.")

        val body = """{"userId": 1}"""

        mvc
            .post("/api/analysis/symbol/005930/commentary") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isServiceUnavailable() }
                jsonPath("$.error") { value("ANALYSIS_UNAVAILABLE") }
                jsonPath("$.message") { exists() }
            }
    }
}

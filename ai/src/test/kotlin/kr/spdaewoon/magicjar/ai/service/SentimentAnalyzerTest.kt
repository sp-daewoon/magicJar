package kr.spdaewoon.magicjar.ai.service

import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kr.spdaewoon.magicjar.ai.prompt.PromptLoader
import kr.spdaewoon.magicjar.core.domain.NewsItem
import kr.spdaewoon.magicjar.core.port.AiClient
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

class SentimentAnalyzerTest {
    private val promptLoader = mockk<PromptLoader>(relaxed = true)
    private val aiClient = mockk<AiClient>()

    init {
        every { promptLoader.load(any()) } returns "SYSTEM_PROMPT"
    }

    private val analyzer = SentimentAnalyzer(aiClient, promptLoader)

    @Test
    fun `빈 뉴스 목록은 Optional empty`() {
        analyzer.analyze(emptyList()).isPresent shouldBe false
    }

    @Test
    fun `JSON 정상 파싱 - 종목별 점수 매핑`() {
        val json =
            """
            {
              "overallSentiment": "POSITIVE",
              "score": 0.4,
              "perInstrument": [
                {"code": "005930", "sentiment": "POSITIVE", "score": 0.7},
                {"code": "000660", "sentiment": "NEGATIVE", "score": -0.3}
              ],
              "eventTags": ["earnings"],
              "tradingHint": "BUY_BIAS",
              "summary": "실적 서프라이즈",
              "confidence": 0.82,
              "reasoning": "삼성전자 분기 실적 서프라이즈 발표"
            }
            """.trimIndent()
        every { aiClient.chatJson(any(), any(), any()) } returns json

        val result = analyzer.analyze(sampleNews()).get()
        result.overallSentiment shouldBe "POSITIVE"
        result.tradingHint shouldBe "BUY_BIAS"
        result.scoreByCode["005930"] shouldBe BigDecimal("0.70")
        result.scoreByCode["000660"] shouldBe BigDecimal("-0.30")
        result.confidence shouldBe BigDecimal("0.82")
        result.parsingFailed shouldBe false
    }

    @Test
    fun `AiClient null 반환 시 Optional empty`() {
        every { aiClient.chatJson(any(), any(), any()) } returns null
        analyzer.analyze(sampleNews()).isPresent shouldBe false
    }

    @Test
    fun `잘못된 JSON은 parsingFailed true 반환`() {
        every { aiClient.chatJson(any(), any(), any()) } returns "not a json at all"
        val result = analyzer.analyze(sampleNews()).get()
        result.parsingFailed shouldBe true
        result.reasoning shouldBe "parsing_failed"
    }

    private fun sampleNews(): List<NewsItem> =
        listOf(
            NewsItem(
                id = UUID.randomUUID(),
                source = "DART",
                publishedAt = Instant.now(),
                title = "삼성전자 분기 실적",
                body = "분기 매출 역대 최고",
                instruments = listOf("005930"),
            ),
        )
}

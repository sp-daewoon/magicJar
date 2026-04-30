package com.mj.consumer.infrastructure.ai

import com.mj.consumer.application.ai.prompt.PromptLoader
import com.mj.domain.ai.SentimentScore
import com.mj.domain.ai.port.NewsSentimentAnalyzer
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.model.ChatResponse
import org.springframework.ai.chat.prompt.Prompt
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * AnthropicNewsSentimentAnalyzer 단위 테스트.
 *
 * 시나리오 4개:
 *  1. POSITIVE JSON 정상 응답 → SentimentScore.Positive
 *  2. NEUTRAL JSON 정상 응답 → SentimentScore.Neutral
 *  3. NEGATIVE JSON (```json 펜스 포함) → SentimentScore.Negative + 펜스 제거 동작
 *  4. malformed JSON → null (parsing 실패는 호출 측이 진행)
 *
 * ChatClient는 fluent API(prompt.call.content)이므로 ChatClient.CallResponseSpec까지 mock한다.
 */
class AnthropicNewsSentimentAnalyzerTest {
    private val promptLoader =
        mockk<PromptLoader>().also {
            every { it.load(any()) } returns "당신은 한국 주식 뉴스 감성 분석가다."
        }

    private fun analyzerWithResponse(content: String?): AnthropicNewsSentimentAnalyzer {
        val chatClient = mockk<ChatClient>()
        val requestSpec = mockk<ChatClient.ChatClientRequestSpec>()
        val callSpec = mockk<ChatClient.CallResponseSpec>()

        every { chatClient.prompt(any<Prompt>()) } returns requestSpec
        every { requestSpec.call() } returns callSpec
        every { callSpec.content() } returns content
        every { callSpec.chatResponse() } returns mockk<ChatResponse>(relaxed = true)

        return AnthropicNewsSentimentAnalyzer(chatClient, promptLoader)
    }

    private fun analyzerWithError(): AnthropicNewsSentimentAnalyzer {
        val chatClient = mockk<ChatClient>()
        every { chatClient.prompt(any<Prompt>()) } throws RuntimeException("upstream 503")
        return AnthropicNewsSentimentAnalyzer(chatClient, promptLoader)
    }

    private fun sampleNews() =
        NewsSentimentAnalyzer.NewsAnalysisInput(
            newsId = UUID.randomUUID(),
            source = "naver",
            publishedAt = Instant.parse("2026-04-30T01:00:00Z"),
            title = "삼성전자 1분기 영업이익 사상 최대",
            body = "삼성전자가 1분기 영업이익 14조원으로 시장 기대치 30% 초과 달성했다.",
            instruments = listOf("005930"),
        )

    @Test
    fun `positive JSON parses into SentimentScore_Positive`() {
        val json =
            """
            {"sentiment":"POSITIVE","confidence":0.85,"rationale":"1분기 영업이익이 시장 기대치를 30% 상회했다는 본문 근거에 기반."}
            """.trimIndent()
        val analyzer = analyzerWithResponse(json)

        val result = analyzer.analyze(sampleNews())

        result shouldNotBe null
        result.shouldBeInstanceOf<SentimentScore.Positive>()
        result!!.label shouldBe SentimentScore.Label.POSITIVE
        result.confidence shouldBe BigDecimal("0.85")
        (result.rationale.contains("기대치") || result.rationale.contains("영업이익")) shouldBe true
    }

    @Test
    fun `neutral JSON parses into SentimentScore_Neutral`() {
        val json =
            """
            {"sentiment":"NEUTRAL","confidence":0.4,"rationale":"단순 정기 공시로 시장 영향 제한적."}
            """.trimIndent()
        val analyzer = analyzerWithResponse(json)

        val result = analyzer.analyze(sampleNews())

        result.shouldBeInstanceOf<SentimentScore.Neutral>()
        result.confidence shouldBe BigDecimal("0.40")
    }

    @Test
    fun `negative JSON wrapped in markdown code fence is unwrapped and parsed`() {
        val fenced =
            """
            ```json
            {"sentiment":"NEGATIVE","confidence":0.72,"rationale":"실적 가이던스 하향 조정 발표로 단기 부정."}
            ```
            """.trimIndent()
        val analyzer = analyzerWithResponse(fenced)

        val result = analyzer.analyze(sampleNews())

        result.shouldBeInstanceOf<SentimentScore.Negative>()
        result.confidence shouldBe BigDecimal("0.72")
    }

    @Test
    fun `malformed JSON returns null (parsing failed)`() {
        val malformed = "이건 JSON이 아니라 그냥 텍스트입니다. sentiment=positive 정도?"
        val analyzer = analyzerWithResponse(malformed)

        val result = analyzer.analyze(sampleNews())

        result shouldBe null
    }

    @Test
    fun `chat client throwing exception twice yields null`() {
        val analyzer = analyzerWithError()

        val result = analyzer.analyze(sampleNews())

        result shouldBe null
    }

    @Test
    fun `empty content yields null`() {
        val analyzer = analyzerWithResponse("")

        val result = analyzer.analyze(sampleNews())

        result shouldBe null
    }
}

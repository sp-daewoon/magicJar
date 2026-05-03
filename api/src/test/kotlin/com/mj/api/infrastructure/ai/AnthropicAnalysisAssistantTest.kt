package com.mj.api.infrastructure.ai

import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Test
import org.springframework.ai.chat.client.ChatClient

/**
 * AnthropicAnalysisAssistant 어댑터 단위 테스트.
 *
 * Spring AI [ChatClient.prompt(...).call().content()] 체인을 mockk로 감싸 응답 시나리오 검증.
 */
class AnthropicAnalysisAssistantTest {
    private val loader = AnalysisPromptLoader()
    private val builder = AnalysisPromptBuilder(loader)
    private val props = AnalysisAiProperties(enabled = true, provider = "anthropic", model = "claude-sonnet-4-5")

    private fun mockChatClient(content: String?): ChatClient {
        val client = mockk<ChatClient>()
        val req = mockk<ChatClient.ChatClientRequestSpec>()
        val callResp = mockk<ChatClient.CallResponseSpec>()
        every { client.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } returns req
        every { req.call() } returns callResp
        every { callResp.content() } returns content
        return client
    }

    private fun symbolReq() =
        AnalysisRequest.SymbolCommentary(
            userId = 1L,
            code = "005930",
            contextSnapshot =
                AnalysisRequest.SymbolContextSnapshot(
                    nameKr = "삼성전자",
                    sector = "반도체",
                    lastClose = 72500.0,
                    change1d = 1.2,
                ),
        )

    @Test
    fun `success returns Result with text and tokens`() {
        val client = mockChatClient("본 종목은 단기 추세가 우상향 흐름입니다. 추가로 거래량 확인이 필요합니다.")
        val sut = AnthropicAnalysisAssistant(client, builder, props)

        val result = sut.analyze(symbolReq()).getOrThrow()
        result.model shouldBe "claude-sonnet-4-5"
        result.text shouldContain "[면책]" // ensureDisclaimer auto-append
        result.tokensUsed.input shouldBe (result.tokensUsed.input) // sanity (>0)
        (result.tokensUsed.input > 0) shouldBe true
        (result.tokensUsed.output > 0) shouldBe true
    }

    @Test
    fun `LLM already includes disclaimer no double append`() {
        val withDisclaimer =
            "본 종목은 단기 추세가 우상향 흐름입니다.\n\n" + AnalysisResult.DISCLAIMER
        val client = mockChatClient(withDisclaimer)
        val sut = AnthropicAnalysisAssistant(client, builder, props)

        val result = sut.analyze(symbolReq()).getOrThrow()
        // 면책 1번만 등장
        result.text.split("[면책]").size shouldBe 2 // split 결과 = 등장횟수+1
    }

    @Test
    fun `empty response returns Result failure`() {
        val client = mockChatClient("")
        val sut = AnthropicAnalysisAssistant(client, builder, props)

        val outcome = sut.analyze(symbolReq())
        outcome.isFailure shouldBe true
    }

    @Test
    fun `null response returns Result failure`() {
        val client = mockChatClient(null)
        val sut = AnthropicAnalysisAssistant(client, builder, props)

        val outcome = sut.analyze(symbolReq())
        outcome.isFailure shouldBe true
    }

    @Test
    fun `chat client throwing returns Result failure not throw`() {
        val client = mockk<ChatClient>()
        every { client.prompt(any<org.springframework.ai.chat.prompt.Prompt>()) } throws RuntimeException("upstream timeout")
        val sut = AnthropicAnalysisAssistant(client, builder, props)

        val outcome = sut.analyze(symbolReq())
        outcome.isFailure shouldBe true
        outcome.exceptionOrNull()?.message shouldBe "upstream timeout"
    }

    @Test
    fun `cost estimate uses configured prices`() {
        val client = mockChatClient("간단한 응답.")
        val customProps =
            AnalysisAiProperties(
                enabled = true,
                pricePerInputToken = 0.000_002,
                pricePerOutputToken = 0.000_010,
            )
        val sut = AnthropicAnalysisAssistant(client, builder, customProps)

        val result = sut.analyze(symbolReq()).getOrThrow()
        val expected =
            result.tokensUsed.input * customProps.pricePerInputToken +
                result.tokensUsed.output * customProps.pricePerOutputToken
        result.costEstimate shouldBe expected
    }
}

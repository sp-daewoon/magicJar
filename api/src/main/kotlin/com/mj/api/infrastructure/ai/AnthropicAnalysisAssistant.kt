package com.mj.api.infrastructure.ai

import com.mj.domain.ai.analysis.AnalysisRequest
import com.mj.domain.ai.analysis.AnalysisResult
import com.mj.domain.ai.analysis.port.AnalysisAssistant
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import kotlin.system.measureTimeMillis

/**
 * Anthropic Claude 기반 AnalysisAssistant 어댑터.
 *
 * 활성 조건 (등록 시점):
 * - `magicjar.ai.enabled=true`
 * - `magicjar.ai.provider=anthropic`
 * - Spring AI Anthropic ChatModel 빈 존재 (= ANTHROPIC_API_KEY 유효)
 * - `magicjar.ai.mock-mode in (auto, real)` (mock 강제 시 [MockAnalysisAssistant] 우선)
 *
 * graceful failure: 호출·파싱 실패는 [Result.failure] 반환 (예외 throw 금지).
 */
class AnthropicAnalysisAssistant(
    private val chatClient: ChatClient,
    private val promptBuilder: AnalysisPromptBuilder,
    private val props: AnalysisAiProperties,
) : AnalysisAssistant {
    private val log = LoggerFactory.getLogger(AnthropicAnalysisAssistant::class.java)

    override val providerName: String = "anthropic"

    override fun analyze(request: AnalysisRequest): Result<AnalysisResult> {
        return try {
            val (systemPrompt, userPrompt) = promptBuilder.build(request)
            var raw: String? = null
            val durationMs =
                measureTimeMillis {
                    raw =
                        chatClient
                            .prompt(Prompt(listOf(SystemMessage(systemPrompt), UserMessage(userPrompt))))
                            .call()
                            .content()
                }
            val text = raw?.trim().orEmpty()
            if (text.isBlank()) {
                log.warn("AnthropicAnalysisAssistant empty response (type={}, userId={})", request::class.simpleName, request.userId)
                return Result.failure(IllegalStateException("LLM empty response"))
            }

            // 면책 문구 자동 보정 (LLM이 누락하더라도 항상 포함)
            val finalText = ensureDisclaimer(text)

            // 토큰 정확치는 어댑터 응답 메타에서 직접 회수 어렵 (Spring AI 추상). 대략 추정 (1 token ≈ 한국어 1.3자).
            val approxInTokens = ((systemPrompt.length + userPrompt.length) / 1.3).toInt()
            val approxOutTokens = (finalText.length / 1.3).toInt()
            val cost =
                approxInTokens * props.pricePerInputToken +
                    approxOutTokens * props.pricePerOutputToken

            Result.success(
                AnalysisResult(
                    text = finalText,
                    confidence = DEFAULT_CONFIDENCE,
                    model = props.model,
                    promptVersion = AnalysisPromptLoader.PROMPT_VERSION,
                    tokensUsed =
                        AnalysisResult.TokensUsed(
                            input = approxInTokens,
                            output = approxOutTokens,
                        ),
                    costEstimate = cost,
                    durationMs = durationMs,
                ),
            )
        } catch (ex: Exception) {
            log.warn(
                "AnthropicAnalysisAssistant call failed (type={}, userId={}): {}",
                request::class.simpleName,
                request.userId,
                ex.message,
            )
            Result.failure(ex)
        }
    }

    private fun ensureDisclaimer(text: String): String {
        val disclaimer = AnalysisResult.DISCLAIMER
        return if (text.contains("[면책]")) text else "$text\n\n$disclaimer"
    }

    companion object {
        // LLM이 직접 confidence 산출하지 않으므로 어댑터 기본값. post-MVP에서 LLM 출력 파싱.
        const val DEFAULT_CONFIDENCE = 0.6
    }
}

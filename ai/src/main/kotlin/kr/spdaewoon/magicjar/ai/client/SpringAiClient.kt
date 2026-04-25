package kr.spdaewoon.magicjar.ai.client

import kr.spdaewoon.magicjar.ai.config.AiProperties
import kr.spdaewoon.magicjar.core.port.AiClient
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatOptions
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt

/**
 * Spring AI ChatClient 기반 Anthropic Claude 클라이언트.
 * - `core.port.AiClient` 구현
 * - 기본 모델: `claude-sonnet-4-6` (설정으로 교체 가능)
 * - `chatJson`: schema 문자열을 system prompt 말미에 삽입해 JSON-only 응답을 강제.
 *
 * 주의: Anthropic prompt caching(cacheControl=ephemeral)은 Spring AI 1.0.x에서
 *       Anthropic starter가 자동 적용하는 system message 우회 경로에 의존한다.
 *       현재 M3 레벨에서는 ChatClient가 메시지 캐싱 힌트를 전송하지 않으므로,
 *       프롬프트 첫 머리에 일관된 시스템 지시를 두어 공급자 측 캐시 친화적 구조만 유지한다.
 */
class SpringAiClient(
    private val chatClient: ChatClient,
    private val properties: AiProperties,
) : AiClient {
    private val log = LoggerFactory.getLogger(SpringAiClient::class.java)

    override fun chat(
        systemPrompt: String,
        userMessage: String,
    ): String? = callInternal(systemPrompt, userMessage, jsonMode = false, timeoutSeconds = properties.timeout.seconds)

    override fun chatJson(
        systemPrompt: String,
        userMessage: String,
        schema: String,
    ): String? {
        val system =
            buildString {
                appendLine(systemPrompt.trim())
                appendLine()
                appendLine("## 출력 포맷 (반드시 준수)")
                appendLine("아래 JSON 스키마를 엄격히 따르는 **JSON 하나**만 반환하라. 주석/마크다운/설명문 금지.")
                appendLine("```json")
                appendLine(schema.trim())
                appendLine("```")
            }
        return callInternal(system, userMessage, jsonMode = true, timeoutSeconds = properties.timeout.seconds)
    }

    private fun callInternal(
        systemPrompt: String,
        userMessage: String,
        jsonMode: Boolean,
        timeoutSeconds: Long,
    ): String? =
        try {
            val options =
                AnthropicChatOptions
                    .builder()
                    .withModel(properties.model)
                    .withTemperature(properties.temperature)
                    .withMaxTokens(properties.maxTokens)
                    .build()
            val prompt =
                Prompt(
                    listOf(SystemMessage(systemPrompt), UserMessage(userMessage)),
                    options,
                )
            val response =
                chatClient
                    .prompt(prompt)
                    .call()
                    .content()
            if (response.isNullOrBlank()) {
                log.warn("SpringAiClient returned empty content (jsonMode={})", jsonMode)
                null
            } else if (jsonMode) {
                extractJson(response)
            } else {
                response
            }
        } catch (ex: Exception) {
            log.warn(
                "SpringAiClient call failed (jsonMode={}, timeoutSec={}): {}",
                jsonMode,
                timeoutSeconds,
                ex.message,
            )
            null
        }

    /**
     * LLM이 ```json ... ``` 펜스를 두르더라도 JSON 본문만 추출.
     */
    private fun extractJson(raw: String): String {
        val trimmed = raw.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return trimmed
        val fenceStart = trimmed.indexOf("```")
        if (fenceStart < 0) return trimmed
        val afterFence = trimmed.substring(fenceStart + 3)
        val nl = afterFence.indexOf('\n')
        val body = if (nl >= 0) afterFence.substring(nl + 1) else afterFence
        val end = body.lastIndexOf("```")
        return if (end >= 0) body.substring(0, end).trim() else body.trim()
    }
}

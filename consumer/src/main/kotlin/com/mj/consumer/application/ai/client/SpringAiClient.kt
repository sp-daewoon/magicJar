package com.mj.consumer.application.ai.client

import com.mj.consumer.config.ai.AiProperties
import com.mj.domain.ai.port.AiClient
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt
import org.springframework.ai.openai.OpenAiChatOptions

/**
 * Spring AI ChatClient 기반 OpenAI 클라이언트.
 * - `domain.ai.port.AiClient` 구현
 * - 기본 모델: `gpt-4o-mini` (설정으로 교체 가능)
 * - `chatJson`: schema 문자열을 system prompt 말미에 삽입해 JSON-only 응답을 강제.
 */
class SpringAiClient(
    private val chatClient: ChatClient,
    private val properties: AiProperties,
) : AiClient {
    private val log = LoggerFactory.getLogger(SpringAiClient::class.java)

    override fun chat(
        systemPrompt: String,
        userMessage: String,
    ): String? = callInternal(systemPrompt, userMessage, jsonMode = false)

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
        return callInternal(system, userMessage, jsonMode = true)
    }

    private fun callInternal(
        systemPrompt: String,
        userMessage: String,
        jsonMode: Boolean,
    ): String? =
        try {
            val options =
                OpenAiChatOptions
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
            log.warn("SpringAiClient call failed (jsonMode={}): {}", jsonMode, ex.message)
            null
        }

    /** LLM이 ```json ... ``` 펜스를 두르더라도 JSON 본문만 추출. */
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

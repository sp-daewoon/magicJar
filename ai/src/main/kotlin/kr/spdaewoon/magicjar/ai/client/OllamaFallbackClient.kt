package kr.spdaewoon.magicjar.ai.client

import kr.spdaewoon.magicjar.ai.config.AiProperties
import kr.spdaewoon.magicjar.core.port.AiClient
import org.slf4j.LoggerFactory
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.chat.messages.SystemMessage
import org.springframework.ai.chat.messages.UserMessage
import org.springframework.ai.chat.prompt.Prompt

/**
 * 로컬 Ollama 폴백 클라이언트. `magicjar.ai.fallback.enabled=true`일 때만 빈 등록.
 * API 키가 없거나 외부 LLM이 불능인 상황에서도 간단한 응답을 제공해 개발 흐름 유지.
 *
 * 품질은 평가용이며, 운영에서는 primary(Claude) 사용을 권장한다.
 */
class OllamaFallbackClient(
    private val chatClient: ChatClient,
    private val properties: AiProperties,
) : AiClient {
    private val log = LoggerFactory.getLogger(OllamaFallbackClient::class.java)

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
                appendLine("## 출력 포맷 (반드시 JSON)")
                appendLine(schema.trim())
            }
        return callInternal(system, userMessage, jsonMode = true)
    }

    private fun callInternal(
        systemPrompt: String,
        userMessage: String,
        jsonMode: Boolean,
    ): String? =
        try {
            val prompt =
                Prompt(
                    listOf(SystemMessage(systemPrompt), UserMessage(userMessage)),
                )
            chatClient
                .prompt(prompt)
                .call()
                .content()
                ?.trim()
                ?.ifBlank { null }
        } catch (ex: Exception) {
            log.warn("OllamaFallbackClient call failed (jsonMode={}): {}", jsonMode, ex.message)
            null
        }
}

package com.mj.consumer.application.ai.client

import com.mj.domain.ai.port.AiClient
import org.slf4j.LoggerFactory

/**
 * Primary 실패 시 Fallback으로 자동 전환하는 Composite 클라이언트.
 * fallback이 null이면 primary만 사용한다.
 */
class AiClientComposite(
    private val primary: AiClient,
    private val fallback: AiClient?,
) : AiClient {
    private val log = LoggerFactory.getLogger(AiClientComposite::class.java)

    override fun chat(
        systemPrompt: String,
        userMessage: String,
    ): String? {
        val primaryResult =
            runCatching { primary.chat(systemPrompt, userMessage) }.getOrElse {
                log.warn("primary.chat failed: {}", it.message)
                null
            }
        if (primaryResult != null) return primaryResult
        return fallback?.let {
            log.debug("falling back to {}", it::class.simpleName)
            runCatching { it.chat(systemPrompt, userMessage) }.getOrNull()
        }
    }

    override fun chatJson(
        systemPrompt: String,
        userMessage: String,
        schema: String,
    ): String? {
        val primaryResult =
            runCatching { primary.chatJson(systemPrompt, userMessage, schema) }.getOrElse {
                log.warn("primary.chatJson failed: {}", it.message)
                null
            }
        if (primaryResult != null) return primaryResult
        return fallback?.let {
            runCatching { it.chatJson(systemPrompt, userMessage, schema) }.getOrNull()
        }
    }
}

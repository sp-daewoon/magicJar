package com.mj.consumer.application.ai.client

import com.mj.domain.ai.port.AiClient
import org.slf4j.LoggerFactory

/** AI API 키 미설정 시 사용되는 no-op 구현체. 모든 메서드가 null을 반환한다. */
object NoOpAiClient : AiClient {
    private val log = LoggerFactory.getLogger(NoOpAiClient::class.java)

    override fun chat(
        systemPrompt: String,
        userMessage: String,
    ): String? {
        log.debug("NoOpAiClient.chat() called — AI 비활성 상태")
        return null
    }

    override fun chatJson(
        systemPrompt: String,
        userMessage: String,
        schema: String,
    ): String? {
        log.debug("NoOpAiClient.chatJson() called — AI 비활성 상태")
        return null
    }
}

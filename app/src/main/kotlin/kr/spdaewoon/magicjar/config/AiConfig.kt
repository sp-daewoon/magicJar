package kr.spdaewoon.magicjar.config

import kr.spdaewoon.magicjar.core.port.AiClient
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * AI 설정: ai 모듈이 실제 AiClient 빈을 등록하지 않은 경우(예: ANTHROPIC_API_KEY 미설정),
 * NoOpAiClient를 대체 주입해 키 없이도 애플리케이션이 정상 부팅되도록 한다.
 */
@Configuration
class AiConfig {
    @Bean
    @ConditionalOnMissingBean(AiClient::class)
    fun noOpAiClient(): AiClient = NoOpAiClient
}

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

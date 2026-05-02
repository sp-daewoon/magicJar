package com.mj.domain.ai.config

import com.mj.domain.ai.client.AiClientComposite
import com.mj.domain.ai.client.OllamaFallbackClient
import com.mj.domain.ai.client.SpringAiClient
import com.mj.domain.ai.port.AiClient
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.ollama.OllamaChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * ai 모듈 Spring 구성.
 *
 * 동작:
 * - `ANTHROPIC_API_KEY`가 설정된 경우 Spring AI `AnthropicChatModel` 빈이 자동 구성되어 존재.
 *   이때만 `SpringAiClient`를 `AiClient`로 등록 (Primary).
 *   → app의 `NoOpAiClient`는 `@ConditionalOnMissingBean`에 의해 자동 해제된다.
 * - 키 미설정 시 빈 없음 → app의 `NoOpAiClient`가 유지되어 AI 비활성 부팅.
 * - `magicjar.ai.fallback.enabled=true` + Ollama 가동 시 폴백 체인 구성.
 */
@Configuration
@EnableConfigurationProperties(AiProperties::class)
class AiModuleConfig {
    @Bean
    @ConditionalOnBean(AnthropicChatModel::class)
    fun anthropicChatClient(chatModel: AnthropicChatModel): ChatClient = ChatClient.builder(chatModel).build()

    @Bean
    @ConditionalOnBean(AnthropicChatModel::class)
    fun primarySpringAiClient(
        @org.springframework.beans.factory.annotation.Qualifier("anthropicChatClient") chatClient: ChatClient,
        props: AiProperties,
    ): SpringAiClient = SpringAiClient(chatClient, props)

    @Bean
    @ConditionalOnProperty(prefix = "magicjar.ai.fallback", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(OllamaChatModel::class)
    fun ollamaChatClient(chatModel: OllamaChatModel): ChatClient = ChatClient.builder(chatModel).build()

    @Bean
    @ConditionalOnProperty(prefix = "magicjar.ai.fallback", name = ["enabled"], havingValue = "true")
    @ConditionalOnBean(OllamaChatModel::class)
    fun ollamaFallbackClient(
        @org.springframework.beans.factory.annotation.Qualifier("ollamaChatClient") chatClient: ChatClient,
        props: AiProperties,
    ): OllamaFallbackClient = OllamaFallbackClient(chatClient, props)

    /**
     * Primary AiClient. SpringAiClient가 있는 경우에만 등록되므로,
     * 키가 없을 때는 이 빈이 생성되지 않고 app의 NoOpAiClient가 유지된다.
     */
    @Bean
    @Primary
    @ConditionalOnBean(SpringAiClient::class)
    fun aiClient(
        primary: SpringAiClient,
        fallbackProvider: ObjectProvider<OllamaFallbackClient>,
    ): AiClient {
        val fallback = fallbackProvider.ifAvailable
        return AiClientComposite(primary = primary, fallback = fallback)
    }
}

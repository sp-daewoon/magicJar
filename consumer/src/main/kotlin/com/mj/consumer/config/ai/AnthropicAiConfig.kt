package com.mj.consumer.config.ai

import com.mj.consumer.application.ai.prompt.PromptLoader
import com.mj.consumer.infrastructure.ai.AnthropicNewsSentimentAnalyzer
import com.mj.domain.ai.port.NewsSentimentAnalyzer
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * Anthropic Claude 통합 활성화.
 *
 * 활성 조건 (3개 모두 충족):
 *   1. `magicjar.ai.enabled=true` (default false)
 *   2. Spring AI Anthropic autoconfig가 [AnthropicChatModel] 빈을 등록 (= 키가 유효)
 *   3. application.yml의 `spring.ai.anthropic.api-key` 채워짐
 *
 * 키 부재·토글 OFF 시 빈 자체가 등록되지 않음 → 호출 측은 `NewsSentimentAnalyzer` 의존을 옵셔널로 처리.
 */
@Configuration
@ConditionalOnProperty(prefix = "magicjar.ai", name = ["enabled"], havingValue = "true")
@ConditionalOnBean(AnthropicChatModel::class)
class AnthropicAiConfig {
    @Bean(name = ["anthropicChatClient"])
    fun anthropicChatClient(chatModel: AnthropicChatModel): ChatClient = ChatClient.builder(chatModel).build()

    @Bean
    fun newsSentimentAnalyzer(
        @org.springframework.beans.factory.annotation.Qualifier("anthropicChatClient")
        chatClient: ChatClient,
        promptLoader: PromptLoader,
    ): NewsSentimentAnalyzer = AnthropicNewsSentimentAnalyzer(chatClient, promptLoader)
}

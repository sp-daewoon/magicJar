package com.mj.consumer.config.ai

import com.mj.consumer.application.ai.client.AiClientComposite
import com.mj.consumer.application.ai.client.NoOpAiClient
import com.mj.consumer.application.ai.client.SpringAiClient
import com.mj.domain.ai.port.AiClient
import org.springframework.ai.chat.client.ChatClient
import org.springframework.ai.openai.OpenAiChatModel
import org.springframework.beans.factory.ObjectProvider
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * AI 모듈 Spring 구성.
 *
 * - OPENAI_API_KEY가 설정된 경우 Spring AI OpenAiChatModel 빈이 자동 구성됨 → SpringAiClient 등록.
 * - 키 미설정 시 빈 없음 → NoOpAiClient가 유지되어 AI 비활성 부팅.
 */
@Configuration
@EnableConfigurationProperties(AiProperties::class)
class AiModuleConfig {
    @Bean
    @ConditionalOnBean(OpenAiChatModel::class)
    fun openAiChatClient(chatModel: OpenAiChatModel): ChatClient = ChatClient.builder(chatModel).build()

    @Bean
    @ConditionalOnBean(OpenAiChatModel::class)
    fun primarySpringAiClient(
        chatClient: ChatClient,
        props: AiProperties,
    ): SpringAiClient = SpringAiClient(chatClient, props)

    @Bean
    @Primary
    @ConditionalOnBean(SpringAiClient::class)
    fun aiClient(
        primary: SpringAiClient,
        fallbackProvider: ObjectProvider<AiClient>,
    ): AiClient = AiClientComposite(primary = primary, fallback = null)

    @Bean
    @ConditionalOnMissingBean(AiClient::class)
    fun noOpAiClient(): AiClient = NoOpAiClient
}

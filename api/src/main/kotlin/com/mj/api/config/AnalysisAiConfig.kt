package com.mj.api.config

import com.mj.api.infrastructure.ai.AnalysisAiProperties
import com.mj.api.infrastructure.ai.AnalysisPromptBuilder
import com.mj.api.infrastructure.ai.AnalysisPromptLoader
import com.mj.api.infrastructure.ai.AnthropicAnalysisAssistant
import com.mj.api.infrastructure.ai.MockAnalysisAssistant
import com.mj.domain.ai.analysis.port.AnalysisAssistant
import org.slf4j.LoggerFactory
import org.springframework.ai.anthropic.AnthropicChatModel
import org.springframework.ai.chat.client.ChatClient
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

/**
 * AI 분석 보조 빈 등록 (T16, ADR 0046).
 *
 * 빈 결정 트리:
 * 1. `magicjar.ai.enabled=false` → 어떤 [AnalysisAssistant]도 등록 X (controller 503).
 * 2. `magicjar.ai.mock-mode=mock` → [ForcedMockConfig]가 [MockAnalysisAssistant] 강제 등록.
 * 3. `mock-mode in (auto, real)` + `provider=anthropic` + AnthropicChatModel 빈
 *    → [AnthropicAnalysisAssistant].
 * 4. `mock-mode=auto` + AnthropicChatModel 빈 미존재 → [MockAnalysisAssistant] fallback.
 * 5. `mock-mode=real` + 키 미설정 → 어떤 빈도 등록 X (controller 503).
 */
@Configuration
@EnableConfigurationProperties(AnalysisAiProperties::class)
@ConditionalOnProperty(prefix = "magicjar.ai", name = ["enabled"], havingValue = "true")
class AnalysisAiConfig {
    private val log = LoggerFactory.getLogger(AnalysisAiConfig::class.java)

    @Bean
    fun analysisPromptBuilder(loader: AnalysisPromptLoader): AnalysisPromptBuilder = AnalysisPromptBuilder(loader)

    /** anthropic chat client 후보 (chat model 빈 있을 때만). */
    @Bean(name = ["analysisAnthropicChatClient"])
    @ConditionalOnBean(AnthropicChatModel::class)
    fun analysisAnthropicChatClient(chatModel: AnthropicChatModel): ChatClient = ChatClient.builder(chatModel).build()

    /** mock-mode=mock → 강제 mock. */
    @Configuration
    @ConditionalOnProperty(prefix = "magicjar.ai", name = ["mock-mode"], havingValue = "mock")
    class ForcedMockConfig {
        private val log = LoggerFactory.getLogger(ForcedMockConfig::class.java)

        @Bean
        fun forcedMockAnalysisAssistant(): AnalysisAssistant {
            log.info("AnalysisAssistant: MockAnalysisAssistant (forced — mock-mode=mock)")
            return MockAnalysisAssistant()
        }
    }

    /** mock-mode=auto: anthropic 시도 → 없으면 mock fallback. */
    @Configuration
    @ConditionalOnProperty(prefix = "magicjar.ai", name = ["mock-mode"], havingValue = "auto", matchIfMissing = true)
    class AutoModeConfig {
        private val log = LoggerFactory.getLogger(AutoModeConfig::class.java)

        @Bean
        @ConditionalOnBean(name = ["analysisAnthropicChatClient"])
        @ConditionalOnProperty(prefix = "magicjar.ai", name = ["provider"], havingValue = "anthropic")
        fun anthropicAnalysisAssistant(
            @Qualifier("analysisAnthropicChatClient") chatClient: ChatClient,
            promptBuilder: AnalysisPromptBuilder,
            props: AnalysisAiProperties,
        ): AnalysisAssistant {
            log.info("AnalysisAssistant: AnthropicAnalysisAssistant (auto, provider=anthropic, model={})", props.model)
            return AnthropicAnalysisAssistant(chatClient, promptBuilder, props)
        }

        @Bean
        @ConditionalOnMissingBean(AnalysisAssistant::class)
        fun fallbackMockAnalysisAssistant(props: AnalysisAiProperties): AnalysisAssistant {
            log.info("AnalysisAssistant: MockAnalysisAssistant (auto fallback — provider={}, key absent)", props.provider)
            return MockAnalysisAssistant()
        }
    }

    /** mock-mode=real: anthropic만 시도. 빈 미등록 시 controller 503. */
    @Configuration
    @ConditionalOnProperty(prefix = "magicjar.ai", name = ["mock-mode"], havingValue = "real")
    class RealOnlyConfig {
        private val log = LoggerFactory.getLogger(RealOnlyConfig::class.java)

        @Bean
        @ConditionalOnBean(name = ["analysisAnthropicChatClient"])
        @ConditionalOnProperty(prefix = "magicjar.ai", name = ["provider"], havingValue = "anthropic")
        fun anthropicAnalysisAssistantReal(
            @Qualifier("analysisAnthropicChatClient") chatClient: ChatClient,
            promptBuilder: AnalysisPromptBuilder,
            props: AnalysisAiProperties,
        ): AnalysisAssistant {
            log.info("AnalysisAssistant: AnthropicAnalysisAssistant (real, provider=anthropic, model={})", props.model)
            return AnthropicAnalysisAssistant(chatClient, promptBuilder, props)
        }
    }
}

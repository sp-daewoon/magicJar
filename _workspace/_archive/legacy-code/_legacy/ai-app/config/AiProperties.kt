package com.mj.domain.ai.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * ai 모듈 설정 프로퍼티 (application-ai.yml의 `magicjar.ai.*`와 매핑).
 * application-ai.yml은 app 쪽의 `spring.config.import`를 통해 선택적으로 로드된다.
 */
@ConfigurationProperties(prefix = "magicjar.ai")
data class AiProperties(
    val provider: String = "anthropic",
    val model: String = "claude-sonnet-4-6",
    val temperature: Double = 0.3,
    val maxTokens: Int = 4096,
    val timeout: Duration = Duration.ofSeconds(30),
    val longTimeout: Duration = Duration.ofSeconds(60),
    val fallback: FallbackProperties = FallbackProperties(),
    val promptCache: PromptCacheProperties = PromptCacheProperties(),
) {
    data class FallbackProperties(
        val enabled: Boolean = false,
        val provider: String = "ollama",
        val model: String = "llama3.1:8b",
    )

    data class PromptCacheProperties(
        // Anthropic prompt caching: ephemeral
        val enabled: Boolean = true,
    )
}

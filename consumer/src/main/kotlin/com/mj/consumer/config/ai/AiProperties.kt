package com.mj.consumer.config.ai

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * AI 모듈 설정 프로퍼티 (`magicjar.ai.*`와 매핑).
 */
@ConfigurationProperties(prefix = "magicjar.ai")
data class AiProperties(
    val provider: String = "openai",
    val model: String = "gpt-4o-mini",
    val temperature: Double = 0.3,
    val maxTokens: Int = 4096,
    val timeout: Duration = Duration.ofSeconds(30),
    val longTimeout: Duration = Duration.ofSeconds(60),
    val fallback: FallbackProperties = FallbackProperties(),
) {
    data class FallbackProperties(
        val enabled: Boolean = false,
        val provider: String = "ollama",
        val model: String = "llama3.1:8b",
    )
}

package kr.spdaewoon.magicjar.ai.client

import io.github.resilience4j.circuitbreaker.CircuitBreaker
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig
import io.github.resilience4j.kotlin.circuitbreaker.executeFunction
import kr.spdaewoon.magicjar.core.port.AiClient
import org.slf4j.LoggerFactory
import java.time.Duration

/**
 * Primary(Anthropic) 실패 시 Fallback(Ollama)으로 자동 전환하는 Composite 클라이언트.
 * Resilience4j CircuitBreaker를 이용해 반복 실패 시 일시적으로 primary 호출을 차단한다.
 */
class AiClientComposite(
    private val primary: AiClient,
    private val fallback: AiClient?,
    cbName: String = "ai-primary",
) : AiClient {
    private val log = LoggerFactory.getLogger(AiClientComposite::class.java)

    private val breaker: CircuitBreaker =
        CircuitBreaker.of(
            cbName,
            CircuitBreakerConfig
                .custom()
                .failureRateThreshold(60.0f)
                .slowCallRateThreshold(80.0f)
                .slowCallDurationThreshold(Duration.ofSeconds(45))
                .minimumNumberOfCalls(5)
                .slidingWindowSize(10)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .permittedNumberOfCallsInHalfOpenState(2)
                .build(),
        )

    override fun chat(
        systemPrompt: String,
        userMessage: String,
    ): String? {
        val primaryResult =
            runCatching {
                breaker.executeFunction { primary.chat(systemPrompt, userMessage) }
            }.getOrElse {
                log.warn("primary.chat failed (breaker={}): {}", breaker.state, it.message)
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
            runCatching {
                breaker.executeFunction { primary.chatJson(systemPrompt, userMessage, schema) }
            }.getOrElse {
                log.warn("primary.chatJson failed (breaker={}): {}", breaker.state, it.message)
                null
            }
        if (primaryResult != null) return primaryResult
        return fallback?.let {
            runCatching { it.chatJson(systemPrompt, userMessage, schema) }.getOrNull()
        }
    }
}

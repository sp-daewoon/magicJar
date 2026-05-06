package kr.spdaewoon.magicjar.api.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * WebClient Bean 설정 (ADR 0046 §4-6).
 * KisAuthValidator 등 외부 REST 호출에 사용.
 * 운영용 KisAuthAdapter (Redis 분산락 + 토큰 cache)는 PR-7+ T-W2에서 별도 구성.
 */
@Configuration
class WebClientConfig {
    @Bean
    fun webClient(): WebClient = WebClient.builder().build()
}

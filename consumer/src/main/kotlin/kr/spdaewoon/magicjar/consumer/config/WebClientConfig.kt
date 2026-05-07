package kr.spdaewoon.magicjar.consumer.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * Consumer WebClient Bean 설정 (ADR 0042 Bootstrap — KIS REST 호출).
 * timeout 5s (KisBalanceAdapter 내부에서 reactorClientHttpConnector로 설정 예정).
 */
@Configuration
class WebClientConfig {
    @Bean
    fun webClient(): WebClient = WebClient.builder().build()
}

package kr.spdaewoon.magicjar.batch.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.function.client.WebClient

/**
 * Batch WebClient Bean 설정 (PR-16 hotfix — KisInstrumentMasterAdapter consumer→batch 이동).
 * KIS 종목 마스터 다운로드 시 KisInstrumentMasterAdapter가 의존.
 */
@Configuration
class WebClientConfig {
    @Bean
    fun webClient(): WebClient = WebClient.builder().build()
}

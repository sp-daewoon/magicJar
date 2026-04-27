package com.mj.api.config

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.kotlinModule
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Primary

/**
 * Jackson 전역 설정.
 * - Java time module (ISO-8601, ZonedDateTime 등)
 * - camelCase 유지 (snake_case 변환 금지 — spec §4)
 * - null 제외 직렬화 (application.yml default-property-inclusion: non_null 보완)
 */
@Configuration
class JacksonConfig {
    @Bean
    @Primary
    fun objectMapper(): ObjectMapper =
        ObjectMapper()
            .registerModule(JavaTimeModule())
            .registerModule(kotlinModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .setDefaultPropertyInclusion(
                com.fasterxml.jackson.annotation.JsonInclude.Value.construct(
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                    com.fasterxml.jackson.annotation.JsonInclude.Include.NON_NULL,
                ),
            )
}

package com.mj.consumer.config

import com.mj.consumer.infrastructure.kis.KisHttpSession
import com.mj.consumer.infrastructure.secret.AesGcmSecretCipher
import com.mj.domain.secret.SecretCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * 1번 트랙 KIS 관련 Bean 등록.
 * spec §6.1, §8.2.
 *
 * KisHttpSession은 mode별로 두 개 운영 (MOCK_KIS·REAL_KIS는 baseUrl 다름).
 */
@Configuration
class ConsumerKisConfig {
    @Bean
    fun secretCipher(
        @Value("\${magicjar.secret.master-key:}") masterKeyB64: String,
    ): SecretCipher {
        require(masterKeyB64.isNotBlank()) {
            "magicjar.secret.master-key (env MAGICJAR_SECRET_MASTER_KEY) is required for SecretCipher"
        }
        val raw = Base64.getDecoder().decode(masterKeyB64)
        require(raw.size == 32) {
            "master key must be 32 bytes after base64 decode (got ${raw.size})"
        }
        return AesGcmSecretCipher(SecretKeySpec(raw, "AES"))
    }

    @Bean("mockKisHttpSession")
    fun mockKisHttpSession(
        @Value("\${magicjar.kis.mock.base-url:https://openapivts.koreainvestment.com:29443}")
        baseUrl: String,
    ): KisHttpSession = KisHttpSession(baseUrl)

    @Bean("realKisHttpSession")
    fun realKisHttpSession(
        @Value("\${magicjar.kis.real.base-url:https://openapi.koreainvestment.com:9443}")
        baseUrl: String,
    ): KisHttpSession = KisHttpSession(baseUrl)
}

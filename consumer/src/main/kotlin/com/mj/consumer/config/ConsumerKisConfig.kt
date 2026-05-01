package com.mj.consumer.config

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
}

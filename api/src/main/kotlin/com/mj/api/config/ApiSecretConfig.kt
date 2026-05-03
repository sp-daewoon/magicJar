package com.mj.api.config

import com.mj.api.infrastructure.secret.ApiAesGcmSecretCipher
import com.mj.domain.secret.SecretCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * api 모듈용 SecretCipher Bean 등록.
 *
 * consumer 모듈의 ConsumerKisConfig와 동일한 AES-256-GCM 구현.
 * dev·prd profile에서만 활성 — mock 환경에서는 master key 미설정이어도 통과.
 *
 * 인증 endpoint(T18a)에서 appKey·appSecret 암호화/복호화에 사용.
 */
@Configuration
class ApiSecretConfig {
    @Bean
    @Profile("dev | prd")
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
        return ApiAesGcmSecretCipher(SecretKeySpec(raw, "AES"))
    }

    /** mock profile — AuthService가 암호화 없이 plain 저장하는 stub. */
    @Bean
    @Profile("mock")
    fun noopSecretCipher(): SecretCipher = NoopSecretCipher()
}

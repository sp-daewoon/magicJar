package com.mj.consumer.config

import com.mj.consumer.application.market.HoldingsCodeFetcher
import com.mj.consumer.infrastructure.kis.KisHttpSession
import com.mj.consumer.infrastructure.secret.AesGcmSecretCipher
import com.mj.domain.secret.SecretCipher
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import java.util.Base64
import javax.crypto.spec.SecretKeySpec

/**
 * 1번 트랙 KIS 관련 Bean 등록.
 * spec §6.1, §8.2.
 *
 * 모든 Bean이 `dev | prd`에서만 활성 — mock profile에서는:
 *  - SecretCipher 사용처 0 (DbKisCredentialProvider도 dev|prd)
 *  - KisHttpSession 사용처 0 (RealKisOrderAdapter도 dev|prd)
 *  → mock 부팅 시 master key·KIS host 미설정이어도 통과.
 *
 * KisHttpSession은 mode별로 두 개 운영 (MOCK_KIS·REAL_KIS는 baseUrl 다름).
 */
@Configuration
class ConsumerKisConfig {
    /**
     * R2-A1 stub: HoldingsCodeFetcher — 빈 List 반환.
     * 1번 트랙 dogfooding 일시 중단 중이라 빈 구현으로 시작.
     * R2-A3 watchlist 도입 시 DB 어댑터로 교체.
     */
    @Bean
    @ConditionalOnMissingBean(HoldingsCodeFetcher::class)
    fun stubHoldingsCodeFetcher(): HoldingsCodeFetcher = HoldingsCodeFetcher { emptyList() }

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
        return AesGcmSecretCipher(SecretKeySpec(raw, "AES"))
    }

    @Bean("mockKisHttpSession")
    @Profile("dev | prd")
    fun mockKisHttpSession(
        @Value("\${magicjar.kis.mock.base-url:https://openapivts.koreainvestment.com:29443}")
        baseUrl: String,
    ): KisHttpSession = KisHttpSession(baseUrl)

    @Bean("realKisHttpSession")
    @Profile("dev | prd")
    fun realKisHttpSession(
        @Value("\${magicjar.kis.real.base-url:https://openapi.koreainvestment.com:9443}")
        baseUrl: String,
    ): KisHttpSession = KisHttpSession(baseUrl)
}

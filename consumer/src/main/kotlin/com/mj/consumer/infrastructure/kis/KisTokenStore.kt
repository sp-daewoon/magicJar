package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS access token 캐시 + 발급기 (consumer 모듈 InMemory 구현).
 *
 * R3-5c-3 PR5: [KisTokenStorePort] 구현체로 일반화.
 * `magicjar.kis.redis-token.enabled=false` (default) 시 활성. true 시
 * [com.mj.consumer.infrastructure.kis.auth.ConsumerRedisKisTokenStoreAdapter] 가 활성화되며 본 어댑터는 미등록.
 *
 * - 동일 앱키당 동시 1개 권장이라 in-process AtomicReference 로 충분.
 * - TTL 기본 23h(만료 1h 전 갱신 여유).
 * - appKey/appSecret이 비었으면 발급을 시도하지 않고 IllegalStateException 던진다.
 */
@Component
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class KisTokenStore(
    private val properties: KisProperties,
    private val clock: Clock = Clock.systemUTC(),
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) : KisTokenStorePort {
    private val log = LoggerFactory.getLogger(KisTokenStore::class.java)
    private val cached = AtomicReference<CachedToken?>()

    private val restClient: RestClient =
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .build()

    /**
     * R3-5c-3 PR5: 도메인 port 시그니처 추가.
     * scope 분리는 consumer 모듈의 신규 path. 기존 caller(`currentTokenOrIssue()`)는 deprecated overload로 호환.
     */
    override fun currentTokenOrIssue(scope: KisAuthScope): String = currentTokenOrIssue()

    override fun invalidate(scope: KisAuthScope) = invalidate()

    fun currentTokenOrIssue(): String {
        val now = clock.instant()
        cached.get()?.let { if (it.isValid(now)) return it.token }
        return synchronized(this) {
            cached.get()?.let { if (it.isValid(now)) return it.token }
            val issued = issueToken()
            cached.set(issued)
            issued.token
        }
    }

    fun invalidate() {
        cached.set(null)
    }

    private fun issueToken(): CachedToken {
        check(properties.hasCredentials()) {
            "KIS appKey/appSecret 미설정. 시세/주문 호출 전에 환경변수를 채우거나 mockMode 샘플을 사용하세요."
        }

        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to properties.appKey,
                "appsecret" to properties.appSecret,
            )

        log.info("Requesting KIS access token (mockMode={})", properties.mockMode)
        val response =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(body)
                .retrieve()
                .body(TokenResponse::class.java)
                ?: error("KIS token response is null")

        val expiresAt = clock.instant().plusSeconds(properties.tokenTtlSeconds)
        return CachedToken(response.accessToken, expiresAt)
    }

    internal data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    ) {
        // R3-5c: 사용자 정책 — KIS 토큰 24h 유효, 23h 시점에 갱신 유도 (1h 마진).
        fun isValid(now: Instant): Boolean = now.isBefore(expiresAt.minus(Duration.ofHours(1)))
    }

    internal data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("token_type") val tokenType: String? = null,
        @JsonProperty("expires_in") val expiresIn: Long? = null,
    )
}

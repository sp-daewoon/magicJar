package com.mj.domain.market.kis

import com.fasterxml.jackson.annotation.JsonProperty
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS access token 캐시 + 발급기.
 *
 * - 동일 앱키당 동시 1개 권장이라 in-process AtomicReference 로 충분.
 * - TTL 기본 23h(만료 1h 전 갱신 여유).
 * - appKey/appSecret이 비었으면 발급을 시도하지 않고 IllegalStateException 던진다.
 */
@Component
class KisTokenStore(
    private val properties: KisProperties,
    private val clock: Clock = Clock.systemUTC(),
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(KisTokenStore::class.java)
    private val cached = AtomicReference<CachedToken?>()

    private val restClient: RestClient =
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json")
            .build()

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
        fun isValid(now: Instant): Boolean = now.isBefore(expiresAt.minus(Duration.ofMinutes(5)))
    }

    internal data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
        @JsonProperty("token_type") val tokenType: String? = null,
        @JsonProperty("expires_in") val expiresIn: Long? = null,
    )
}

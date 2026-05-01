package com.mj.consumer.infrastructure.kis

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.time.Clock
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * KIS REST 토큰 캐시 + 발급/만료/재발급 + RestClient 공유.
 * spec §6.1, ADR 0011·0021. PR #32 토큰 로직을 추출해 시세·주문 어댑터가 공유.
 *
 * 단일 baseUrl (KIS 모의 또는 실전 host) per session — 하나의 KIS 환경에 대해 토큰 캐시.
 * 사용자별 appKey 단위로 토큰을 캐시하므로 멀티테넌트 진입 시 그대로 작동한다.
 *
 * RestClient 기반 (기존 RealKisRestAdapter와 동일 패턴):
 * - HTTP/1.1 강제 (SimpleClientHttpRequestFactory, JDK HttpURLConnection)
 *   — JDK HttpClient의 HTTP/2 시도가 WireMock·일부 KIS 서버와 충돌 방지
 * - 401 응답 시 토큰 invalidate + 1회 재발급 후 retry
 */
class KisHttpSession(
    private val baseUrl: String,
    private val clock: Clock = Clock.systemUTC(),
    val client: RestClient =
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build(),
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cache = ConcurrentHashMap<String, CachedToken>()

    fun baseUrl(): String = baseUrl

    /** appKey 단위 토큰 발급/캐시. 만료 1분 전엔 재발급. */
    fun acquireToken(
        appKey: String,
        appSecret: String,
    ): String {
        val now = Instant.now(clock)
        val cached = cache[appKey]
        if (cached != null && cached.expiresAt.isAfter(now.plusSeconds(MARGIN_SEC))) {
            return cached.token
        }
        return refresh(appKey, appSecret, now)
    }

    fun invalidateToken(appKey: String) {
        cache.remove(appKey)
        log.info("[KIS_TOKEN] invalidated appKey={}***", appKey.take(4))
    }

    /**
     * 401 응답 시 토큰을 invalidate 하고 1회 재발급 후 retry.
     * 재시도 시도 후에도 실패하면 그대로 throw.
     */
    fun <T> executeWithRetry(
        appKey: String,
        appSecret: String,
        action: () -> T,
    ): T {
        // 사전 토큰 확보 (없으면 발급)
        acquireToken(appKey, appSecret)
        return try {
            action()
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[KIS_TOKEN] 401 — invalidate + retry once")
                invalidateToken(appKey)
                acquireToken(appKey, appSecret)
                action()
            } else {
                throw ex
            }
        }
    }

    private fun refresh(
        appKey: String,
        appSecret: String,
        now: Instant,
    ): String {
        log.info("[KIS_TOKEN] refresh appKey={}*** baseUrl={}", appKey.take(4), baseUrl)
        val resp =
            client
                .post()
                .uri("/oauth2/tokenP")
                .body(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "appkey" to appKey,
                        "appsecret" to appSecret,
                    ),
                ).retrieve()
                .onStatus(HttpStatusCode::isError) { _, r ->
                    error("[KIS_TOKEN] refresh failed http=${r.statusCode}")
                }.body(TokenResponse::class.java)
                ?: error("[KIS_TOKEN] refresh empty response")
        val token = resp.accessToken
        val ttl = resp.expiresIn
        cache[appKey] = CachedToken(token, now.plusSeconds(ttl))
        log.info("[KIS_TOKEN] refreshed ttl={}s", ttl)
        return token
    }

    private data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    )

    /** KIS /oauth2/tokenP 응답. snake_case → camelCase 매핑. */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class TokenResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("access_token") val accessToken: String = "",
        @com.fasterxml.jackson.annotation.JsonProperty("expires_in") val expiresIn: Long = 86400,
        @com.fasterxml.jackson.annotation.JsonProperty("token_type") val tokenType: String = "Bearer",
    )

    private companion object {
        const val MARGIN_SEC = 60L
    }
}

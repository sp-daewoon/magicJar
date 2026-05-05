package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS 토큰 단일 store — api 모듈 in-memory 구현. ADR 0050 R3-5c-2 / R3-5c-3.
 *
 * 정책 (`docs/architecture/external-api-policy.md` §2):
 *   - 24h 유효 토큰, **23h 시점에 갱신 유도** (1h 마진)
 *   - scope별 분리 ([KisAuthScope.System.mode] = "vps"·"prod") — 키·base URL 다름
 *   - 동시 발급 lock — `synchronized` (단일 JVM 내 충분, multi-JVM 분산 lock은 R3-5c-3 PR2~PR5 Redis 어댑터)
 *
 * 본 구현은 R3-5c-3 단계에서도 유지 — `magicjar.kis.redis-token.enabled=false` 디폴트일 때 활성. PR5에서
 * Redis 어댑터로 전환 시 `@ConditionalOnProperty(... matchIfMissing=true)` 게이팅 추가 예정.
 */
@Component
class InMemoryKisTokenStoreAdapter(
    @Value("\${spring.kis.base-url:https://openapi.koreainvestment.com:9443}")
    private val prodBaseUrl: String,
    @Value("\${spring.kis.app-key:}")
    private val prodAppKey: String,
    @Value("\${spring.kis.app-secret:}")
    private val prodAppSecret: String,
) : KisTokenStorePort {
    private val log = LoggerFactory.getLogger(javaClass)

    /** scope.tokenKey() → cached token. R3-5c-3: scope별 캐시 키 분리. */
    private val tokens = ConcurrentHashMap<String, AtomicReference<CachedToken?>>()

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(prodBaseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    override fun currentTokenOrIssue(scope: KisAuthScope): String {
        val key = scope.tokenKey()
        val ref = tokens.computeIfAbsent(key) { AtomicReference() }
        val now = Instant.now()
        ref.get()?.let { if (it.isValid(now)) return it.token }
        return synchronized(this) {
            ref.get()?.let { if (it.isValid(now)) return it.token }
            val (appKey, appSecret) = credentialsFor(scope)
            require(appKey.isNotBlank() && appSecret.isNotBlank()) {
                "[kis-token] 키 미설정 scope=${scope.identifier()}"
            }
            val issued = issueToken(appKey, appSecret, scope)
            ref.set(issued)
            issued.token
        }
    }

    override fun invalidate(scope: KisAuthScope) {
        tokens[scope.tokenKey()]?.set(null)
        log.info("[kis-token] invalidate scope={}", scope.identifier())
    }

    /**
     * api 모듈은 시스템 키 prod 모드만 지원 (사용자 정책: 데이터 조회는 항상 실전).
     * 사용자 키는 consumer 모듈의 [com.mj.domain.market.kis.port.KisTokenStorePort]
     * 사용자 scope 구현체(R3-5c-3 PR5)에서 처리.
     */
    private fun credentialsFor(scope: KisAuthScope): Pair<String, String> =
        when (scope) {
            is KisAuthScope.System ->
                when (scope.mode) {
                    "prod" -> prodAppKey to prodAppSecret
                    else ->
                        throw IllegalArgumentException(
                            "[kis-token] api 모듈은 prod 시스템 키만 지원 (받음: ${scope.identifier()})",
                        )
                }
            is KisAuthScope.User ->
                throw IllegalArgumentException(
                    "[kis-token] api 모듈은 시스템 키만 지원 (사용자 scope=${scope.identifier()}는 consumer 모듈에서 처리)",
                )
        }

    private fun issueToken(
        appKey: String,
        appSecret: String,
        scope: KisAuthScope,
    ): CachedToken {
        log.info(
            "[kis-token] 발급 scope={} baseUrl={} appKey={}…",
            scope.identifier(),
            prodBaseUrl,
            appKey.take(8),
        )
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to appKey,
                "appsecret" to appSecret,
            )
        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(body)
                .retrieve()
                .body(TokenResponse::class.java)
                ?: error("[kis-token] null 응답")
        val expiresAt = Instant.now().plusSeconds(86_400L) // KIS 24h 표준
        log.info("[kis-token] 발급 성공 scope={} expiresAt={}", scope.identifier(), expiresAt)
        return CachedToken(resp.accessToken, expiresAt)
    }

    internal data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    ) {
        // R3-5c: 23h 시점에 갱신 유도 (1h 마진)
        fun isValid(now: Instant): Boolean = now.isBefore(expiresAt.minus(Duration.ofHours(1)))
    }

    internal data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
    )
}

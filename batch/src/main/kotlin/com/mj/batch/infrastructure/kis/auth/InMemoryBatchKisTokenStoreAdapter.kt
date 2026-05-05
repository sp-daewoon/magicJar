package com.mj.batch.infrastructure.kis.auth

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.batch.infrastructure.kis.KisRestProperties
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

/**
 * R3-5c-3 PR4 — batch 모듈 InMemory 토큰 store (Redis 비활성 default).
 *
 * `magicjar.kis.redis-token.enabled=false` (default) 시 활성. PR5 docker-compose env로
 * Redis 어댑터 활성 시 자동 비활성. dev/prd profile만 (mock·real-kis stub 어댑터는 토큰 무관).
 */
@Component("inMemoryBatchKisTokenStoreAdapter")
@Profile("dev | prd")
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class InMemoryBatchKisTokenStoreAdapter(
    private val props: KisRestProperties,
) : KisTokenStorePort {
    private val log = LoggerFactory.getLogger(javaClass)

    private val tokens = ConcurrentHashMap<String, AtomicReference<CachedToken?>>()

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    override fun currentTokenOrIssue(scope: KisAuthScope): String {
        validateScope(scope)
        val key = scope.tokenKey()
        val ref = tokens.computeIfAbsent(key) { AtomicReference() }
        val now = Instant.now()
        ref.get()?.let { if (it.isValid(now)) return it.token }
        return synchronized(this) {
            ref.get()?.let { if (it.isValid(now)) return it.token }
            require(props.appKey.isNotBlank() && props.appSecret.isNotBlank()) {
                "[kis-token] 키 미설정 scope=${scope.identifier()}"
            }
            val issued = issueToken(scope)
            ref.set(issued)
            issued.token
        }
    }

    override fun invalidate(scope: KisAuthScope) {
        tokens[scope.tokenKey()]?.set(null)
        log.info("[kis-token] invalidate scope={}", scope.identifier())
    }

    private fun validateScope(scope: KisAuthScope) {
        when (scope) {
            is KisAuthScope.System ->
                if (scope.mode != "prod") {
                    throw IllegalArgumentException(
                        "[kis-token] batch 모듈은 prod 시스템 키만 지원 (받음: ${scope.identifier()})",
                    )
                }
            is KisAuthScope.User ->
                throw IllegalArgumentException(
                    "[kis-token] batch 모듈은 시스템 키만 지원 (사용자 scope=${scope.identifier()}는 consumer 모듈에서 처리)",
                )
        }
    }

    private fun issueToken(scope: KisAuthScope): CachedToken {
        log.info(
            "[kis-token] 발급 scope={} baseUrl={} appKey={}…",
            scope.identifier(),
            props.baseUrl,
            props.appKey.take(8),
        )
        val body =
            mapOf(
                "grant_type" to "client_credentials",
                "appkey" to props.appKey,
                "appsecret" to props.appSecret,
            )
        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(body)
                .retrieve()
                .body(TokenResponse::class.java)
                ?: error("[kis-token] null 응답 scope=${scope.identifier()}")
        val expiresAt = Instant.now().plusSeconds(86_400L)
        log.info("[kis-token] 발급 성공 scope={} expiresAt={}", scope.identifier(), expiresAt)
        return CachedToken(resp.accessToken, expiresAt)
    }

    internal data class CachedToken(
        val token: String,
        val expiresAt: Instant,
    ) {
        fun isValid(now: Instant): Boolean = now.isBefore(expiresAt.minus(Duration.ofHours(1)))
    }

    internal data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String,
    )
}

package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonProperty
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
 * KIS 토큰 단일 store — api 모듈 in-memory 구현. ADR 0050 R3-5c-2.
 *
 * 정책 (`docs/architecture/external-api-policy.md` §2):
 *   - 24h 유효 토큰, **23h 시점에 갱신 유도** (1h 마진)
 *   - 모드별 분리(vps·prod) — 키·base URL 다름
 *   - 동시 발급 lock — `synchronized` (단일 컨테이너 내 충분)
 *
 * 모듈별 in-memory 구현 — 컨테이너 재시작 시 재발급. Redis 영속(분산 lock)은 후속 사이클.
 *
 * 어댑터 사용:
 *   - KisChartCandleAdapter (R3-5c-2 통합)
 *   - 나머지 5 어댑터는 R3-5c-3에서 점진 통합
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

    /** 모드별 cached token. 현재 api는 prod 만 사용 (사용자 정책 — 데이터 조회는 항상 실전). */
    private val tokens = ConcurrentHashMap<String, AtomicReference<CachedToken?>>()

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(prodBaseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    override fun currentTokenOrIssue(mode: String): String {
        val ref = tokens.computeIfAbsent(mode) { AtomicReference() }
        val now = Instant.now()
        ref.get()?.let { if (it.isValid(now)) return it.token }
        return synchronized(this) {
            ref.get()?.let { if (it.isValid(now)) return it.token }
            val (appKey, appSecret) = credentialsFor(mode)
            require(appKey.isNotBlank() && appSecret.isNotBlank()) {
                "[kis-token] 키 미설정 mode=$mode"
            }
            val issued = issueToken(appKey, appSecret)
            ref.set(issued)
            issued.token
        }
    }

    override fun invalidate(mode: String) {
        tokens[mode]?.set(null)
        log.info("[kis-token] invalidate mode={}", mode)
    }

    private fun credentialsFor(mode: String): Pair<String, String> =
        when (mode) {
            "prod" -> prodAppKey to prodAppSecret
            else -> throw IllegalArgumentException("[kis-token] unsupported mode=$mode (vps는 consumer 모듈에서 처리)")
        }

    private fun issueToken(
        appKey: String,
        appSecret: String,
    ): CachedToken {
        log.info("[kis-token] 발급 baseUrl={} appKey={}…", prodBaseUrl, appKey.take(8))
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
        log.info("[kis-token] 발급 성공 expiresAt={}", expiresAt)
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

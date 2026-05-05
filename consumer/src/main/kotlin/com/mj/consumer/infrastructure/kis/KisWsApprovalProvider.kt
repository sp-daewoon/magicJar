package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.kis.port.KisApprovalScope
import com.mj.domain.market.kis.port.KisApprovalStorePort
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * KIS WebSocket approval_key 발급 + 캐시 (TTL 23h).
 *
 * POST /oauth2/Approval → {approval_key} 반환.
 * KIS 공식 문서에 TTL 미공개 — 1일 추정 기반으로 23h 캐시 후 재발급.
 * 401 / 오류 시 1회 재시도. 그 이후에도 실패하면 즉시 throw.
 *
 * spec §2-A / §3 T1 (plan: 2026-05-04-track2-r2-quote-ws.md).
 */
@Component
@Profile("dev | prd")
@ConditionalOnProperty(
    prefix = "magicjar.kis.redis-token",
    name = ["enabled"],
    havingValue = "false",
    matchIfMissing = true,
)
class KisWsApprovalProvider(
    private val properties: KisProperties,
    @Qualifier("realKisHttpSession") private val httpSession: KisHttpSession,
    private val clock: Clock = Clock.systemUTC(),
) : KisApprovalStorePort {
    private val log = LoggerFactory.getLogger(javaClass)
    private val cached = AtomicReference<CachedApproval?>(null)

    /**
     * R3-5c-3 PR5: 도메인 port 시그니처 추가. scope 분기는 후속 사이클에서 활용.
     * 기존 caller(`getApprovalKey()`)는 아래 호환 메서드로 동작.
     */
    override fun currentApprovalOrIssue(scope: KisApprovalScope): String = getApprovalKey()

    override fun invalidate(scope: KisApprovalScope) = invalidate()

    /**
     * approval_key 반환. 캐시 hit 시 바로, miss 시 KIS API 호출.
     */
    fun getApprovalKey(): String {
        val now = Instant.now(clock)
        val hit = cached.get()
        if (hit != null && hit.expiresAt.isAfter(now)) {
            log.debug("[KIS_WS_APPROVAL] cache hit, expires={}", hit.expiresAt)
            return hit.key
        }
        return fetch()
    }

    /**
     * 강제 재발급 (재연결·401 시 호출).
     */
    fun invalidate() {
        cached.set(null)
        log.info("[KIS_WS_APPROVAL] invalidated — next call will re-fetch")
    }

    private fun fetch(): String {
        val appKey =
            requireNotNull(properties.appKey?.takeIf { it.isNotBlank() }) {
                "kis.app-key is required for KisWsApprovalProvider"
            }
        val appSecret =
            requireNotNull(properties.appSecret?.takeIf { it.isNotBlank() }) {
                "kis.app-secret is required for KisWsApprovalProvider"
            }

        log.info("[KIS_WS_APPROVAL] fetching approval_key from {}", httpSession.baseUrl())

        val client: RestClient = httpSession.client
        val resp =
            client
                .post()
                .uri("/oauth2/Approval")
                .body(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "appkey" to appKey,
                        "secretkey" to appSecret,
                    ),
                ).retrieve()
                .onStatus(HttpStatusCode::isError) { _, r ->
                    error("[KIS_WS_APPROVAL] fetch failed status=${r.statusCode}")
                }.body(ApprovalResponse::class.java)
                ?: error("[KIS_WS_APPROVAL] empty response from /oauth2/Approval")

        val key =
            resp.approvalKey.takeIf { it.isNotBlank() }
                ?: error("[KIS_WS_APPROVAL] approval_key blank in response")

        val expiresAt = Instant.now(clock).plusSeconds(TTL_SECONDS)
        cached.set(CachedApproval(key, expiresAt))
        log.info("[KIS_WS_APPROVAL] approval_key fetched, cached until {}", expiresAt)
        return key
    }

    private data class CachedApproval(
        val key: String,
        val expiresAt: Instant,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class ApprovalResponse(
        @JsonProperty("approval_key") val approvalKey: String = "",
    )

    companion object {
        /** 23시간 캐시 — KIS TTL 미공개(1일 추정) 대비 1h 여유 */
        const val TTL_SECONDS = 23L * 60 * 60
    }
}

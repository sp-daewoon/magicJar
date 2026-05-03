package com.mj.batch.infrastructure.kis.corp_action

import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.batch.infrastructure.kis.KisApiException
import com.mj.batch.infrastructure.kis.KisRestProperties
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * KIS Corp Action 전용 토큰 홀더.
 *
 * RealKisRestAdapter와 동일한 토큰 발급 로직을 재사용하되,
 * Bean 독립성을 위해 별도 클래스로 분리.
 * (RealKisRestAdapter와 토큰을 공유하면 순환 의존 또는 field 공유 문제 발생)
 *
 * dev·prd profile에서만 활성화.
 */
@Component
@Profile("dev | prd")
class KisCorpActionTokenHolder {
    private val log = LoggerFactory.getLogger(KisCorpActionTokenHolder::class.java)

    @Volatile
    private var cachedToken: String? = null

    /** 토큰 반환. 캐시 있으면 반환, 없으면 IllegalStateException (ensureToken 먼저 호출) */
    fun getToken(): String = cachedToken ?: error("[corp-action-token] token not initialized — call ensureToken first")

    /** 토큰 무효화 */
    fun invalidate() {
        cachedToken = null
    }

    /** 캐시된 토큰이 없으면 발급 */
    fun ensureToken(props: KisRestProperties) {
        if (cachedToken != null) return
        cachedToken = issueToken(props)
    }

    private fun issueToken(props: KisRestProperties): String {
        log.info("[corp-action-token] issuing access token (baseUrl={})", props.baseUrl)

        val restClient =
            RestClient
                .builder()
                .baseUrl(props.baseUrl)
                .defaultHeader("Content-Type", "application/json; charset=utf-8")
                .requestFactory(SimpleClientHttpRequestFactory())
                .build()

        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(TokenRequest(appkey = props.appKey, appsecret = props.appSecret))
                .retrieve()
                .onStatus(HttpStatusCode::isError) { _, r ->
                    throw KisApiException(
                        "[corp-action-token] token issue failed — HTTP ${r.statusCode}",
                        rtCd = r.statusCode.value().toString(),
                    )
                }.body(TokenResponse::class.java)
                ?: error("[corp-action-token] token issue failed — null response")

        log.info("[corp-action-token] access token issued")
        return resp.access_token
    }

    private data class TokenRequest(
        val grant_type: String = "client_credentials",
        val appkey: String,
        val appsecret: String,
    )

    private data class TokenResponse(
        @JsonProperty("access_token") val access_token: String,
    )
}

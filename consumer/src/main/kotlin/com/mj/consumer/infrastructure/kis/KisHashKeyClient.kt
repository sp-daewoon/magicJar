package com.mj.consumer.infrastructure.kis

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClientResponseException

/**
 * KIS hashkey 발급 클라이언트. spec §6.4, ADR 0035.
 *
 * KIS POST 요청 시 `hashkey` 헤더를 붙이지 않으면 gateway가 EGW00202 반환.
 * - /uapi/hashkey 에 body(Map)를 JSON POST → SHA256 기반 hashkey 문자열 반환
 * - 발급 결과를 요청별 1회 사용 (캐시 없음 — body가 요청마다 다름)
 * - `MAGICJAR_KIS_HASHKEY_ENABLED=false`면 skip (진단 목적 토글)
 *
 * 참고: KIS 공식 샘플에서 _url_fetch(hashkey_url, headers, body)로 자동 처리되는 부분을
 *   RestClient 환경에서 수동 구현.
 */
@Component
@Profile("dev | prd")
class KisHashKeyClient(
    @org.springframework.beans.factory.annotation.Qualifier("mockKisHttpSession")
    private val mockSession: KisHttpSession,
    @org.springframework.beans.factory.annotation.Qualifier("realKisHttpSession")
    private val realSession: KisHttpSession,
    private val properties: KisHashKeyProperties,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val mapper: ObjectMapper = jacksonObjectMapper()

    /**
     * POST body Map에 대한 hashkey 값 발급.
     * @param isMock 모의계좌 여부 (baseUrl 선택)
     * @param appKey KIS appKey
     * @param appSecret KIS appSecret
     * @param body POST 요청 body (Map)
     * @return hashkey 문자열. `enabled=false`이면 null.
     */
    fun fetchHashKey(
        isMock: Boolean,
        appKey: String,
        appSecret: String,
        body: Map<String, Any>,
    ): String? {
        if (!properties.enabled) return null

        val session = if (isMock) mockSession else realSession
        return try {
            val resp =
                session.client
                    .post()
                    .uri("/uapi/hashkey")
                    .header("appkey", appKey)
                    .header("appsecret", appSecret)
                    .body(body)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, r ->
                        throw RestClientResponseException(
                            "hashkey http=${r.statusCode}",
                            r.statusCode,
                            r.statusText,
                            r.headers,
                            r.body.readAllBytes(),
                            Charsets.UTF_8,
                        )
                    }.body(HashKeyResponse::class.java)
                    ?: return null

            log.debug("[HASHKEY] acquired hashkey={}", resp.hash?.take(8))
            resp.hash
        } catch (ex: Exception) {
            log.warn("[HASHKEY] failed — skipping hashkey header: {}", ex.message)
            null
        }
    }

    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    private data class HashKeyResponse(
        @com.fasterxml.jackson.annotation.JsonProperty("HASH") val hash: String? = null,
    )
}

/**
 * hashkey 기능 토글 프로퍼티. spec §6.4 환경변수 `MAGICJAR_KIS_HASHKEY_ENABLED`.
 */
@org.springframework.boot.context.properties.ConfigurationProperties(prefix = "magicjar.kis")
data class KisHashKeyProperties(
    /** EGW00202 hotfix: POST 요청에 hashkey 헤더 자동 첨부. default true. */
    val hashkeyEnabled: Boolean = true,
) {
    val enabled: Boolean get() = hashkeyEnabled
}

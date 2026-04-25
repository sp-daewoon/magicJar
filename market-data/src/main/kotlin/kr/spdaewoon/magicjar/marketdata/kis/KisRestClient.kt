package kr.spdaewoon.magicjar.marketdata.kis

import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatusCode
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient

/**
 * KIS REST 호출 공통 래퍼.
 *
 * 모든 호출은 다음을 자동 적용한다.
 * - Authorization Bearer 토큰 (KisTokenStore)
 * - appkey / appsecret / tr_id 헤더
 * - 401 응답 시 토큰 무효화 + 1회 재시도
 * - 호출 직전 KisRateLimiter 통과
 *
 * 로그에는 appkey/appsecret 값을 절대 남기지 않는다(마스킹 함수 제공).
 */
@Component
class KisRestClient(
    private val properties: KisProperties,
    private val tokenStore: KisTokenStore,
    private val rateLimiter: KisRateLimiter,
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(KisRestClient::class.java)

    private val client: RestClient =
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .build()

    /** GET 요청 (시세 계열 — quote 버킷 소비). */
    fun <T : Any> get(
        path: String,
        trId: String,
        query: Map<String, String> = emptyMap(),
        responseType: Class<T>,
    ): T {
        rateLimiter.acquireQuote()
        return execute("GET", path, trId, false) { token ->
            client
                .get()
                .uri { builder ->
                    builder.path(path)
                    query.forEach { (k, v) -> builder.queryParam(k, v) }
                    builder.build()
                }.headers { headers -> applyAuthHeaders(headers, token, trId) }
                .retrieve()
                .onStatus(HttpStatusCode::is4xxClientError) { _, response ->
                    error("KIS 4xx ${response.statusCode}: ${response.statusText}")
                }.body(responseType)
                ?: error("KIS empty response: $path")
        }
    }

    /** POST 요청 (주문 계열 — order 버킷 소비). */
    fun <T : Any> postOrder(
        path: String,
        trId: String,
        hashKey: String?,
        body: Any,
        responseType: Class<T>,
    ): T {
        rateLimiter.acquireOrder()
        return execute("POST", path, trId, true) { token ->
            client
                .post()
                .uri(path)
                .headers { headers ->
                    applyAuthHeaders(headers, token, trId)
                    hashKey?.let { headers.set("hashkey", it) }
                }.body(body)
                .retrieve()
                .body(responseType)
                ?: error("KIS empty response: $path")
        }
    }

    private fun applyAuthHeaders(
        headers: org.springframework.http.HttpHeaders,
        token: String,
        trId: String,
    ) {
        headers.set("Authorization", "Bearer $token")
        headers.set("appkey", properties.appKey ?: "")
        headers.set("appsecret", properties.appSecret ?: "")
        headers.set("tr_id", trId)
        headers.set("custtype", "P")
    }

    private fun <T : Any> execute(
        method: String,
        path: String,
        trId: String,
        isOrder: Boolean,
        block: (token: String) -> T,
    ): T {
        if (!properties.hasCredentials()) {
            error("KIS credentials missing — cannot $method $path (trId=$trId, isOrder=$isOrder)")
        }
        return try {
            val token = tokenStore.currentTokenOrIssue()
            log.debug("KIS {} {} trId={} isOrder={}", method, path, trId, isOrder)
            block(token)
        } catch (ex: org.springframework.web.client.HttpClientErrorException.Unauthorized) {
            log.warn("KIS 401 — invalidating token and retrying once (trId={})", trId)
            tokenStore.invalidate()
            block(tokenStore.currentTokenOrIssue())
        }
    }

    companion object {
        /** 로그에 appKey/secret을 노출해야 할 때 강제로 거치는 마스킹 함수. */
        fun mask(value: String?): String =
            when {
                value.isNullOrEmpty() -> "****"
                value.length <= 6 -> "****"
                else -> value.take(4) + "****" + value.takeLast(2)
            }
    }
}

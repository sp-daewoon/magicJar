package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.index.MarketIndex
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.math.BigDecimal

/**
 * KIS 지수 조회 REST 어댑터 (T21-T2).
 *
 * 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-index-price
 * TR_ID: FHPUP02100000 (국내 업종·지수 현재 지수 조회)
 *
 * MCP 검증 요약:
 * - TR_ID: FHPUP02100000
 * - FID_COND_MRKT_DIV_CODE: U (업종지수)
 * - FID_INPUT_ISCD: 0001(KOSPI) / 1001(KOSDAQ)
 * - output 필드: bstp_nmix_prpr(현재지수), bstp_nmix_prdy_vrss(전일대비),
 *   prdy_ctrt(등락률%), acml_vol(누적거래량), acml_tr_pbmn(거래대금),
 *   bstp_nmix_prdy_clpr(전일종가)
 *
 * KIS 키 미설정 시 빈 리스트 반환 (graceful degradation).
 */
@Component
class KisMarketIndexAdapter(
    @Value("\${magicjar.kis.base-url:https://openapivts.koreainvestment.com:29443}")
    private val baseUrl: String,
    @Value("\${magicjar.kis.app-key:}")
    private val appKey: String,
    @Value("\${magicjar.kis.app-secret:}")
    private val appSecret: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    @Volatile
    private var cachedToken: String? = null

    /** 코드 목록으로 지수 조회. KIS 키 미설정 시 빈 리스트. */
    fun fetchMarketIndex(codes: List<String>): List<MarketIndex> {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-index] appKey 미설정 — fetchMarketIndex 건너뜀")
            return emptyList()
        }

        if (cachedToken == null) {
            cachedToken = issueToken() ?: return emptyList()
        }

        return codes.mapNotNull { code ->
            try {
                fetchSingleIndex(code)
            } catch (ex: Exception) {
                log.warn("[kis-index] 지수 조회 실패 code={} error={}", code, ex.message)
                null
            }
        }
    }

    private fun fetchSingleIndex(code: String): MarketIndex? {
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/inquire-index-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "U")
                        .queryParam("FID_INPUT_ISCD", code)
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer ${cachedToken!!}"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHPUP02100000"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        cachedToken = null
                        cachedToken = issueToken() ?: throw RuntimeException("토큰 재발급 실패")
                        // 재시도 시도 생략 — 다음 호출에서 갱신된 토큰 사용
                    }
                    log.warn("[kis-index] HTTP 오류 code={} status={}", code, resp.statusCode)
                    null
                }.body(IndexPriceResponse::class.java)
                ?: return null

        if (response.rtCd != "0") {
            log.warn("[kis-index] rt_cd={} msg={} code={}", response.rtCd, response.msg1, code)
            return null
        }

        return response.toMarketIndex(code)
    }

    private fun issueToken(): String? =
        try {
            val resp =
                restClient
                    .post()
                    .uri("/oauth2/tokenP")
                    .body(TokenRequest(appkey = appKey, appsecret = appSecret))
                    .retrieve()
                    .body(TokenResponse::class.java)
            resp?.accessToken
        } catch (ex: Exception) {
            log.warn("[kis-index] 토큰 발급 실패: {}", ex.message)
            null
        }

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    private data class TokenRequest(
        val grant_type: String = "client_credentials",
        val appkey: String,
        val appsecret: String,
    )

    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class IndexPriceResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: IndexOutput = IndexOutput(),
    ) {
        fun toMarketIndex(code: String): MarketIndex {
            val o = output
            val name =
                when (code) {
                    "0001" -> "KOSPI"
                    "1001" -> "KOSDAQ"
                    else -> o.bstpNmixPrpr.takeIf { it.isNotBlank() } ?: code
                }
            return MarketIndex(
                code = code,
                name = name,
                currentValue = o.bstpNmixPrpr.parseBigDecimal() ?: BigDecimal.ZERO,
                prevClose = o.bstpNmixPrdyClpr.parseBigDecimal() ?: BigDecimal.ZERO,
                changePercent = o.prdyCtrt.parseBigDecimal() ?: BigDecimal.ZERO,
                volume = o.acmlVol.parseLong() ?: 0L,
                session = o.mrktTrndCls.takeIf { it.isNotBlank() },
                tradingValue = o.acmlTrPbmn.parseBigDecimal(),
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class IndexOutput(
        /** 현재 지수 */
        @JsonProperty("bstp_nmix_prpr") val bstpNmixPrpr: String = "",
        /** 전일 대비 */
        @JsonProperty("bstp_nmix_prdy_vrss") val bstpNmixPrdyVrss: String = "",
        /** 등락률(%) */
        @JsonProperty("prdy_ctrt") val prdyCtrt: String = "",
        /** 누적 거래량 */
        @JsonProperty("acml_vol") val acmlVol: String = "",
        /** 거래대금 */
        @JsonProperty("acml_tr_pbmn") val acmlTrPbmn: String = "",
        /** 전일 종가 */
        @JsonProperty("bstp_nmix_prdy_clpr") val bstpNmixPrdyClpr: String = "",
        /** 시장 구분 코드 (장전/장중/장마감) */
        @JsonProperty("mrkt_trad_cls_name") val mrktTrndCls: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim()
                .replace(",", "")
                .takeIf {
                    it.isNotBlank()
                }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().replace(",", "").takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.RecentTrade
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal

/**
 * KIS 실시간 체결 list REST 어댑터.
 *
 * 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-ccnl
 * TR_ID: FHKST01010300
 *
 * MCP + 직접 호출로 실측 검증 완료 (2026-05-04, 005930 기준):
 * - output[]: 최근 30건 체결 리스트
 *   - stck_cntg_hour: 체결 시각 (HHmmss, 예: "155959")
 *   - stck_prpr: 체결 가격 (원)
 *   - cntg_vol: 체결 수량 (주)
 *   - prdy_vrss: 전일 대비 (원, 부호 포함)
 *   - prdy_vrss_sign: 전일 대비 부호 (1=상한/2=상승/3=보합/4=하한/5=하락)
 *   - prdy_ctrt: 전일 대비율 (%)
 *
 * 401 시 토큰 재발급 1회 재시도. KIS 키 미설정 시 빈 리스트 반환.
 */
@Component
class KisRecentTradesAdapter(
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

    /**
     * 종목 최근 체결 리스트 조회 (inquire-ccnl / FHKST01010300).
     * KIS 키 미설정 시 빈 리스트. limit은 1~30 (KIS 응답 최대 30건).
     */
    fun fetchRecentTrades(
        code: String,
        limit: Int = 30,
    ): List<RecentTrade> {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-trades] appKey 미설정 — fetchRecentTrades 건너뜀 code={}", code)
            return emptyList()
        }

        return try {
            if (cachedToken == null) cachedToken = issueToken() ?: return emptyList()
            callRecentTrades(code, limit)
        } catch (ex: KisRecentTradesException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-trades] 401 — 토큰 재발급 후 1회 재시도 code={}", code)
                cachedToken = issueToken() ?: return emptyList()
                runCatching { callRecentTrades(code, limit) }.getOrElse {
                    log.warn("[kis-trades] 재시도 실패 code={} error={}", code, it.message)
                    emptyList()
                }
            } else {
                log.warn("[kis-trades] fetchRecentTrades 실패 code={} error={}", code, ex.message)
                emptyList()
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                cachedToken = issueToken() ?: return emptyList()
                runCatching { callRecentTrades(code, limit) }.getOrElse { emptyList() }
            } else {
                log.warn("[kis-trades] HTTP 오류 code={} status={}", code, ex.statusCode)
                emptyList()
            }
        } catch (ex: Exception) {
            log.warn("[kis-trades] fetchRecentTrades 실패 code={} error={}", code, ex.message)
            emptyList()
        }
    }

    private fun callRecentTrades(
        code: String,
        limit: Int,
    ): List<RecentTrade> {
        log.info("[kis-trades] FHKST01010300 호출 code={}", code)
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/inquire-ccnl")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", code)
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer ${cachedToken!!}"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHKST01010300"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisRecentTradesException("401", 401)
                    }
                    log.warn("[kis-trades] HTTP 오류 code={} status={}", code, resp.statusCode)
                    throw KisRecentTradesException("KIS inquire-ccnl 오류: ${resp.statusCode}", resp.statusCode.value())
                }.body(CcnlResponse::class.java)
                ?: return emptyList()

        log.info("[kis-trades] FHKST01010300 응답 code={} rt_cd={} count={}", code, response.rtCd, response.output.size)

        if (response.rtCd != "0") {
            log.warn("[kis-trades] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return emptyList()
        }

        val actualLimit = minOf(limit, 30)
        return response.output
            .take(actualLimit)
            .mapNotNull { it.toRecentTrade(code) }
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
            log.warn("[kis-trades] 토큰 발급 실패 error={}", ex.message)
            null
        }

    // ── 내부 예외 ─────────────────────────────────────────────────────────────

    private class KisRecentTradesException(
        message: String,
        val statusCode: Int = 0,
    ) : RuntimeException(message)

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    private data class TokenRequest(
        val grant_type: String = "client_credentials",
        val appkey: String,
        val appsecret: String,
    )

    private data class TokenResponse(
        @JsonProperty("access_token") val accessToken: String = "",
    )

    /**
     * FHKST01010300 응답 (inquire-ccnl).
     * output[]는 최근 30건 체결 리스트.
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CcnlResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: List<CcnlItem> = emptyList(),
    )

    /**
     * 체결 단건 응답 항목.
     *
     * 실측 검증 완료 (2026-05-04):
     * - stck_cntg_hour: "155959" (체결 시각 HHmmss)
     * - stck_prpr: "220500" (체결 가격 원)
     * - cntg_vol: "2" (체결 수량 주)
     * - prdy_vrss: "-5500" (전일 대비, 부호 포함)
     * - prdy_vrss_sign: "5" (하락)
     * - prdy_ctrt: "-2.43" (전일 대비율 %)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CcnlItem(
        /** 체결 시각 (HHmmss) */
        @JsonProperty("stck_cntg_hour") val stckCntgHour: String = "",
        /** 체결 가격 (원) */
        @JsonProperty("stck_prpr") val stckPrpr: String = "",
        /** 체결 수량 (주) */
        @JsonProperty("cntg_vol") val cntgVol: String = "",
        /** 전일 대비 (원, 부호 포함) */
        @JsonProperty("prdy_vrss") val prdyVrss: String = "",
        /** 전일 대비 부호 (1=상한/2=상승/3=보합/4=하한/5=하락) */
        @JsonProperty("prdy_vrss_sign") val prdyVrssSign: String = "",
        /** 전일 대비율 (%) */
        @JsonProperty("prdy_ctrt") val prdyCtrt: String = "",
    ) {
        fun toRecentTrade(code: String): RecentTrade? {
            val price = stckPrpr.parseBigDecimal() ?: return null
            return RecentTrade(
                code = code,
                tradeTime = stckCntgHour,
                price = price,
                volume = cntgVol.parseLong() ?: 0L,
                priceChange = prdyVrss.parseBigDecimal() ?: BigDecimal.ZERO,
                priceChangeSign = prdyVrssSign,
                changeRate = prdyCtrt.parseBigDecimal() ?: BigDecimal.ZERO,
            )
        }
    }

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().replace(",", "").takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().replace(",", "").takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

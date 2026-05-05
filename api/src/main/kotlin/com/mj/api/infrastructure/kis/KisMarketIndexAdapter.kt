package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.index.MarketIndex
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
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
 * KIS 지수 조회 REST 어댑터.
 *
 * R3-5c-3 PR3:
 *   - 자체 토큰 관리(`cachedToken`/`issueToken()`) 제거 → [KisTokenStorePort] 주입.
 *   - **변종 폐기**: 401 즉시 재발급 후 그대로 throw → 표준 retry once 패턴 통일 (D10).
 *
 * 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-index-price
 * TR_ID: FHPUP02100000 (국내 업종·지수 현재 지수 조회)
 *
 * KIS 키 미설정 시 빈 리스트 반환 (graceful degradation).
 */
@Component
class KisMarketIndexAdapter(
    @Value("\${magicjar.kis.base-url.prod:\${magicjar.kis.base-url:https://openapivts.koreainvestment.com:29443}}")
    private val baseUrl: String,
    @Value("\${magicjar.kis.system.app-key.prod:\${magicjar.kis.app-key:}}")
    private val appKey: String,
    @Value("\${magicjar.kis.system.app-secret.prod:\${magicjar.kis.app-secret:}}")
    private val appSecret: String,
    private val tokenStore: KisTokenStorePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val authScope: KisAuthScope = KisAuthScope.System("prod")

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    /** 코드 목록으로 지수 조회. KIS 키 미설정 시 빈 리스트. */
    fun fetchMarketIndex(codes: List<String>): List<MarketIndex> {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-index] appKey 미설정 — fetchMarketIndex 건너뜀")
            return emptyList()
        }

        return codes.mapNotNull { code ->
            try {
                executeWithTokenRetry(code) { token -> fetchSingleIndex(code, token) }
            } catch (ex: Exception) {
                log.warn("[kis-index] 지수 조회 실패 code={} error={}", code, ex.message)
                null
            }
        }
    }

    /** 표준 401 retry once 패턴 (R3-5c-3 D10 — 변종 폐기). */
    private fun <T> executeWithTokenRetry(
        code: String,
        action: (String) -> T?,
    ): T? {
        val token = tokenStore.currentTokenOrIssue(authScope)
        return try {
            action(token)
        } catch (ex: KisIndexException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-index] 401 — invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[kis-index] 401 HTTP — invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                log.warn("[kis-index] HTTP 오류 code={} status={}", code, ex.statusCode)
                null
            }
        }
    }

    private fun fetchSingleIndex(
        code: String,
        token: String,
    ): MarketIndex? {
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
                    h["authorization"] = "Bearer $token"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHPUP02100000"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisIndexException("401", 401)
                    }
                    log.warn("[kis-index] HTTP 오류 code={} status={}", code, resp.statusCode)
                    throw KisIndexException(
                        "KIS inquire-index-price 오류: ${resp.statusCode}",
                        resp.statusCode.value(),
                    )
                }.body(IndexPriceResponse::class.java)
                ?: return null

        if (response.rtCd != "0") {
            log.warn("[kis-index] rt_cd={} msg={} code={}", response.rtCd, response.msg1, code)
            return null
        }

        return response.toMarketIndex(code)
    }

    // ── 내부 예외 ─────────────────────────────────────────────────────────────

    private class KisIndexException(
        message: String,
        val statusCode: Int = 0,
    ) : RuntimeException(message)

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

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
                    else -> code
                }
            val currentValue = o.bstpNmixPrpr.parseBigDecimal() ?: BigDecimal.ZERO
            val changeAmount = o.bstpNmixPrdyVrss.parseBigDecimal() ?: BigDecimal.ZERO
            val prevClose =
                if (currentValue > BigDecimal.ZERO) currentValue - changeAmount else BigDecimal.ZERO
            return MarketIndex(
                code = code,
                name = name,
                currentValue = currentValue,
                prevClose = prevClose,
                changePercent = o.bstpNmixPrdyCtrt.parseBigDecimal() ?: BigDecimal.ZERO,
                volume = o.acmlVol.parseLong() ?: 0L,
                session = o.mrktTrndCls.takeIf { it.isNotBlank() },
                tradingValue = o.acmlTrPbmn.parseBigDecimal(),
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class IndexOutput(
        @JsonProperty("bstp_nmix_prpr") val bstpNmixPrpr: String = "",
        @JsonProperty("bstp_nmix_prdy_vrss") val bstpNmixPrdyVrss: String = "",
        @JsonProperty("bstp_nmix_prdy_ctrt") val bstpNmixPrdyCtrt: String = "",
        @JsonProperty("acml_vol") val acmlVol: String = "",
        @JsonProperty("acml_tr_pbmn") val acmlTrPbmn: String = "",
        @JsonProperty("mrkt_trad_cls_name") val mrktTrndCls: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim()
                .replace(",", "")
                .takeIf { it.isNotBlank() }
                ?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().replace(",", "").takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

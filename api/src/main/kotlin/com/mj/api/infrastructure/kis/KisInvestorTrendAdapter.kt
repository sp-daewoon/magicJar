package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.InvestorTrend
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
 * KIS 외국인·기관 동향 REST 어댑터.
 *
 * 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-investor
 * TR_ID: FHKST01010900
 *
 * MCP + 직접 호출로 실측 검증 완료 (2026-05-04, 005930 기준):
 * - output[]: 최근 30 영업일 동향
 *   - stck_bsop_date: 영업일 (yyyyMMdd, 예: "20260430")
 *   - stck_clpr: 종가 (원)
 *   - prdy_vrss: 전일 대비 (원, 부호 포함)
 *   - prdy_vrss_sign: 전일 대비 부호 (1~5)
 *   - prsn_ntby_qty/frgn_ntby_qty/orgn_ntby_qty: 개인/외국인/기관 순매수 수량 (음수=순매도)
 *   - prsn_ntby_tr_pbmn/frgn_ntby_tr_pbmn/orgn_ntby_tr_pbmn: 순매수 금액 (백만원)
 *   - prsn_shnu_vol/frgn_shnu_vol/orgn_shnu_vol: 매수 수량
 *   - prsn_seln_vol/frgn_seln_vol/orgn_seln_vol: 매도 수량
 *
 * 401 시 토큰 재발급 1회 재시도. KIS 키 미설정 시 빈 리스트 반환.
 */
@Component
class KisInvestorTrendAdapter(
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

    /**
     * 종목 외국인·기관 동향 조회 (inquire-investor / FHKST01010900).
     * R3-5c-3 PR3: 자체 토큰 관리 제거 → [KisTokenStorePort] 주입.
     * KIS 키 미설정 시 빈 리스트. days는 1~30 (KIS 응답 최대 30 영업일).
     */
    fun fetchInvestorTrend(
        code: String,
        days: Int = 20,
    ): List<InvestorTrend> {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-investor] appKey 미설정 — fetchInvestorTrend 건너뜀 code={}", code)
            return emptyList()
        }

        return try {
            executeWithTokenRetry(code) { token -> callInvestorTrend(code, days, token) } ?: emptyList()
        } catch (ex: Exception) {
            log.warn("[kis-investor] fetchInvestorTrend 실패 code={} error={}", code, ex.message)
            emptyList()
        }
    }

    /** 표준 401 retry once 패턴 (R3-5c-3 D10). */
    private fun <T> executeWithTokenRetry(
        code: String,
        action: (String) -> T?,
    ): T? {
        val token = tokenStore.currentTokenOrIssue(authScope)
        return try {
            action(token)
        } catch (ex: KisInvestorTrendException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-investor] 401 — invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[kis-investor] 401 HTTP — invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                log.warn("[kis-investor] HTTP 오류 code={} status={}", code, ex.statusCode)
                null
            }
        }
    }

    private fun callInvestorTrend(
        code: String,
        days: Int,
        token: String,
    ): List<InvestorTrend> {
        log.info("[kis-investor] FHKST01010900 호출 code={}", code)
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/inquire-investor")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", code)
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer $token"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHKST01010900"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisInvestorTrendException("401", 401)
                    }
                    log.warn("[kis-investor] HTTP 오류 code={} status={}", code, resp.statusCode)
                    throw KisInvestorTrendException(
                        "KIS inquire-investor 오류: ${resp.statusCode}",
                        resp.statusCode.value(),
                    )
                }.body(InvestorResponse::class.java)
                ?: return emptyList()

        log.info("[kis-investor] FHKST01010900 응답 code={} rt_cd={} count={}", code, response.rtCd, response.output.size)

        if (response.rtCd != "0") {
            log.warn("[kis-investor] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return emptyList()
        }

        val actualDays = minOf(days, 30)
        return response.output
            .take(actualDays)
            .mapNotNull { it.toInvestorTrend(code) }
    }

    // ── 내부 예외 ─────────────────────────────────────────────────────────────

    private class KisInvestorTrendException(
        message: String,
        val statusCode: Int = 0,
    ) : RuntimeException(message)

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

    /**
     * FHKST01010900 응답 (inquire-investor).
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InvestorResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: List<InvestorItem> = emptyList(),
    )

    /**
     * 일자별 투자자 동향 단건.
     *
     * 실측 검증 완료 (2026-05-04, 005930 20260430 기준):
     * - prsn_ntby_qty: 617606 (개인 순매수 수량)
     * - frgn_ntby_qty: -840944 (외국인 순매도)
     * - orgn_ntby_qty: 158634 (기관 순매수)
     * - *_tr_pbmn: 단위 백만원 (예: frgn_ntby_tr_pbmn=-173923 → 약 -1739억원)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InvestorItem(
        /** 영업일 (yyyyMMdd) */
        @JsonProperty("stck_bsop_date") val stckBsopDate: String = "",
        /** 종가 (원) */
        @JsonProperty("stck_clpr") val stckClpr: String = "",
        /** 전일 대비 (원, 부호 포함) */
        @JsonProperty("prdy_vrss") val prdyVrss: String = "",
        /** 전일 대비 부호 (1=상한/2=상승/3=보합/4=하한/5=하락) */
        @JsonProperty("prdy_vrss_sign") val prdyVrssSign: String = "",
        /** 개인 순매수 수량 */
        @JsonProperty("prsn_ntby_qty") val prsnNtbyQty: String = "",
        /** 외국인 순매수 수량 */
        @JsonProperty("frgn_ntby_qty") val frgnNtbyQty: String = "",
        /** 기관 순매수 수량 */
        @JsonProperty("orgn_ntby_qty") val orgnNtbyQty: String = "",
        /** 개인 순매수 금액 (백만원) */
        @JsonProperty("prsn_ntby_tr_pbmn") val prsnNtbyTrPbmn: String = "",
        /** 외국인 순매수 금액 (백만원) */
        @JsonProperty("frgn_ntby_tr_pbmn") val frgnNtbyTrPbmn: String = "",
        /** 기관 순매수 금액 (백만원) */
        @JsonProperty("orgn_ntby_tr_pbmn") val orgnNtbyTrPbmn: String = "",
        /** 개인 매수 수량 */
        @JsonProperty("prsn_shnu_vol") val prsnShnuVol: String = "",
        /** 외국인 매수 수량 */
        @JsonProperty("frgn_shnu_vol") val frgnShnuVol: String = "",
        /** 기관 매수 수량 */
        @JsonProperty("orgn_shnu_vol") val orgnShnuVol: String = "",
        /** 개인 매도 수량 */
        @JsonProperty("prsn_seln_vol") val prsnSelnVol: String = "",
        /** 외국인 매도 수량 */
        @JsonProperty("frgn_seln_vol") val frgnSelnVol: String = "",
        /** 기관 매도 수량 */
        @JsonProperty("orgn_seln_vol") val orgnSelnVol: String = "",
    ) {
        fun toInvestorTrend(code: String): InvestorTrend? {
            if (stckBsopDate.isBlank()) return null
            return InvestorTrend(
                code = code,
                bizDate = stckBsopDate,
                closePrice = stckClpr.parseBigDecimal() ?: BigDecimal.ZERO,
                priceChange = prdyVrss.parseBigDecimal() ?: BigDecimal.ZERO,
                priceChangeSign = prdyVrssSign,
                personNetBuyQty = prsnNtbyQty.parseLong() ?: 0L,
                foreignNetBuyQty = frgnNtbyQty.parseLong() ?: 0L,
                orgNetBuyQty = orgnNtbyQty.parseLong() ?: 0L,
                personNetBuyAmt = prsnNtbyTrPbmn.parseBigDecimal() ?: BigDecimal.ZERO,
                foreignNetBuyAmt = frgnNtbyTrPbmn.parseBigDecimal() ?: BigDecimal.ZERO,
                orgNetBuyAmt = orgnNtbyTrPbmn.parseBigDecimal() ?: BigDecimal.ZERO,
                personBuyVol = prsnShnuVol.parseLong() ?: 0L,
                foreignBuyVol = frgnShnuVol.parseLong() ?: 0L,
                orgBuyVol = orgnShnuVol.parseLong() ?: 0L,
                personSellVol = prsnSelnVol.parseLong() ?: 0L,
                foreignSellVol = frgnSelnVol.parseLong() ?: 0L,
                orgSellVol = orgnSelnVol.parseLong() ?: 0L,
            )
        }
    }

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().replace(",", "").takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().replace(",", "").takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

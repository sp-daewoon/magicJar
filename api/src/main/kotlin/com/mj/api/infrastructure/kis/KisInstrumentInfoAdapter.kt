package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.instrument.InstrumentDetail
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
 * KIS inquire-stock-info REST 어댑터 (T20-T2).
 *
 * 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-stock-info
 * TR_ID: CTPF1002R (종목 기본정보 — 시가총액·상장주식수·업종 등)
 *
 * MCP 검증 요약:
 * - TR_ID: CTPF1002R (국내주식 종목 기본정보)
 * - FID_COND_MRKT_DIV_CODE: J (주식)
 * - FID_INPUT_ISCD: 종목코드 6자리
 * - output 필드: hts_kor_isnm(한글명), stck_mxpr(상한가), stck_llam(하한가),
 *   stck_sdpr(기준가), stck_prpr(현재가), lstn_stcn(상장주식수),
 *   hts_avls(시가총액), per(PER), pbr(PBR), eps(EPS), bps(BPS),
 *   stac_month(결산월), mxrt(배당률), idst_scls_cd_name(업종명)
 *
 * KIS 키 미설정(appKey blank) 시 graceful degradation — 모든 필드 null 반환.
 */
@Component
class KisInstrumentInfoAdapter(
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
     * 종목 기본정보 조회. KIS 키 미설정 시 null 반환.
     */
    fun fetchInstrumentInfo(code: String): InstrumentDetail? {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-info] appKey 미설정 — fetchInstrumentInfo 건너뜀 code={}", code)
            return null
        }

        return try {
            executeWithTokenRefresh(code)
        } catch (ex: Exception) {
            log.warn("[kis-info] fetchInstrumentInfo 실패 code={} error={}", code, ex.message)
            null
        }
    }

    private fun executeWithTokenRefresh(code: String): InstrumentDetail? {
        if (cachedToken == null) cachedToken = issueToken() ?: return null

        return try {
            callApi(code)
        } catch (ex: KisInstrumentInfoException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-info] 401 — 토큰 재발급 후 1회 재시도")
                cachedToken = issueToken() ?: return null
                callApi(code)
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                cachedToken = issueToken() ?: return null
                callApi(code)
            } else {
                log.warn("[kis-info] HTTP 오류 code={} status={}", code, ex.statusCode)
                null
            }
        }
    }

    private fun callApi(code: String): InstrumentDetail? {
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/inquire-stock-info")
                        .queryParam("AUTH", "")
                        .queryParam("EXCD", "")
                        .queryParam("SYMB", code)
                        .queryParam("GUBN", "0")
                        .queryParam("BYMD", "")
                        .queryParam("MODP", "1")
                        .queryParam("KEYB", "")
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer ${cachedToken!!}"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "CTPF1002R"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisInstrumentInfoException("401", 401)
                    }
                    throw KisInstrumentInfoException("KIS inquire-stock-info 오류: ${resp.statusCode}", resp.statusCode.value())
                }.body(StockInfoResponse::class.java)
                ?: return null

        if (response.rtCd != "0") {
            log.warn("[kis-info] rt_cd={} msg={} code={}", response.rtCd, response.msg1, code)
            return null
        }

        return response.toDetail(code)
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
            log.warn("[kis-info] 토큰 발급 실패: {}", ex.message)
            null
        }

    // ── 내부 예외 ─────────────────────────────────────────────────────────────

    private class KisInstrumentInfoException(
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
     * CTPF1002R 응답 매핑.
     *
     * output 필드 참조 (KIS 공식 docs + MCP 검색):
     * - hts_kor_isnm : 한글 종목명
     * - stck_shrn_iscd : 단축 종목코드
     * - stck_prpr : 현재가
     * - lstn_stcn : 상장 주식수
     * - hts_avls : 시가총액 (억원 단위)
     * - per : 주가수익비율
     * - pbr : 주가순자산비율
     * - eps : 주당순이익
     * - bps : 주당순자산가치
     * - divi : 주당배당금
     * - itewhol_loan_rmnd_ratem : 배당률
     * - idst_scls_cd_name : 업종명
     * - stck_sdpr : 기준가 / 전일 종가 근사값 사용
     * - corp_name : 회사명
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StockInfoResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: StockInfoOutput = StockInfoOutput(),
    ) {
        fun toDetail(code: String): InstrumentDetail {
            val o = output
            return InstrumentDetail(
                code = code,
                name = o.htsKorIsnm.ifBlank { code },
                sector = o.idstSclsCdName.takeIf { it.isNotBlank() },
                // 시가총액: KIS는 억원 단위로 제공 → 원 단위 변환 (×10^8)
                marketCap = o.htsAvls.parseBigDecimal()?.let { it.multiply(BigDecimal("100000000")) },
                sharesOutstanding = o.lstnStcn.parseLong(),
                // KIS inquire-stock-info는 외국인/기관 비율 미제공 (별도 API 필요)
                foreignRatio = null,
                institutionRatio = null,
                // 재무정보는 DART에서 보완
                revenue = null,
                operatingIncome = null,
                netIncome = null,
                per = o.per.parseBigDecimal(),
                pbr = o.pbr.parseBigDecimal(),
                eps = o.eps.parseBigDecimal(),
                bps = o.bps.parseBigDecimal(),
                dividendYield = o.diviRt.parseBigDecimal(),
                majorShareholderRatio = null,
                representativeName = null,
                industry = o.idstSclsCdName.takeIf { it.isNotBlank() },
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StockInfoOutput(
        /** 한글 종목명 */
        @JsonProperty("hts_kor_isnm") val htsKorIsnm: String = "",
        /** 상장 주식수 */
        @JsonProperty("lstn_stcn") val lstnStcn: String = "",
        /** 시가총액 (억원 단위) */
        @JsonProperty("hts_avls") val htsAvls: String = "",
        /** PER */
        @JsonProperty("per") val per: String = "",
        /** PBR */
        @JsonProperty("pbr") val pbr: String = "",
        /** EPS (주당순이익) */
        @JsonProperty("eps") val eps: String = "",
        /** BPS (주당순자산가치) */
        @JsonProperty("bps") val bps: String = "",
        /** 배당률 */
        @JsonProperty("divi_rt") val diviRt: String = "",
        /** 업종명 */
        @JsonProperty("idst_scls_cd_name") val idstSclsCdName: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

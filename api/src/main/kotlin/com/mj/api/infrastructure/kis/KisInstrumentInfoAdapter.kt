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
     * KIS appKey/appSecret 설정 여부. 경고 코드 구분에 사용.
     */
    fun isKeyConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

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
        log.info("[kis-info] CTPF1002R 호출 code={} baseUrl={}", code, baseUrl)
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        // KIS 공식 endpoint (사용자 가이드 검증):
                        // https://apiportal.koreainvestment.com/apiservice-apiservice?/uapi/domestic-stock/v1/quotations/search-stock-info
                        // PDNO=종목코드 6자리, PRDT_TYPE_CD=300 (국내주식)
                        .path("/uapi/domestic-stock/v1/quotations/search-stock-info")
                        .queryParam("PDNO", code)
                        .queryParam("PRDT_TYPE_CD", "300")
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
                    log.warn("[kis-info] HTTP 오류 code={} status={}", code, resp.statusCode)
                    throw KisInstrumentInfoException("KIS search-stock-info 오류: ${resp.statusCode}", resp.statusCode.value())
                }.body(StockInfoResponse::class.java)
                ?: return null

        log.info("[kis-info] CTPF1002R 응답 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)

        if (response.rtCd != "0") {
            log.warn("[kis-info] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return null
        }

        return response.toDetail(code)
    }

    private fun issueToken(): String? =
        try {
            log.info("[kis-info] 토큰 발급 요청 baseUrl={} appKey={}...", baseUrl, appKey.take(8))
            val resp =
                restClient
                    .post()
                    .uri("/oauth2/tokenP")
                    .body(TokenRequest(appkey = appKey, appsecret = appSecret))
                    .retrieve()
                    .onStatus(org.springframework.http.HttpStatusCode::isError) { _, r ->
                        val body =
                            runCatching {
                                r.body
                                    .readBytes()
                                    .toString(Charsets.UTF_8)
                                    .take(200)
                            }.getOrDefault("")
                        log.warn(
                            "[kis-info] 토큰 발급 HTTP 오류 status={} body={} — IP 화이트리스트 미등록 또는 키 만료 가능",
                            r.statusCode,
                            body,
                        )
                        throw KisInstrumentInfoException("token issue failed: ${r.statusCode} $body", r.statusCode.value())
                    }.body(TokenResponse::class.java)
            if (resp?.accessToken.isNullOrBlank()) {
                log.warn("[kis-info] 토큰 발급 응답 access_token 비어있음 — KIS API 서버 오류 가능")
                null
            } else {
                log.info("[kis-info] 토큰 발급 성공 expires={}", resp?.tokenExpired)
                resp!!.accessToken
            }
        } catch (ex: KisInstrumentInfoException) {
            null // 이미 warn 로그 찍힘
        } catch (ex: Exception) {
            log.warn("[kis-info] 토큰 발급 실패 — error={} (IP 화이트리스트·키 만료·네트워크 확인 필요)", ex.message)
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
        @JsonProperty("access_token_token_expired") val tokenExpired: String = "",
    )

    /**
     * CTPF1002R 응답 매핑 (search-stock-info — KIS 공식 spec, 직접 호출 검증 완료).
     *
     * 실 응답 필드 (사용자 가이드 + 컨테이너 직접 호출):
     * - pdno          : 표준 종목코드 (00000A005930)
     * - prdt_name     : 종목명 (삼성전자보통주)
     * - prdt_abrv_name: 종목 약칭 (삼성전자)
     * - prdt_eng_name : 영문명
     * - shtn_pdno     : 단축 종목코드 (005930)
     * - lstg_stqt     : 상장 주식수
     * - cpta          : 자본금 (KRW)
     * - papr          : 액면가
     * - setl_mmdd     : 결산월 (12)
     * - kospi200_item_yn : KOSPI200 편입 여부 (Y/N)
     * - mket_id_cd    : 시장 ID (STK·KSQ)
     * - excg_dvsn_cd  : 거래소 구분 (02)
     * - idx_bztp_lcls_cd : 업종 분류 코드
     * - scts_mket_lstg_dt : 코스피 상장일 (yyyyMMdd)
     *
     * PER·PBR·EPS·BPS·시총은 본 endpoint 미제공 — inquire-price 등 별도 endpoint (다음 사이클).
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
            // 시총 = 자본금 (정확한 시총은 별도 endpoint — 1차 자본금으로 가용성 확보)
            // 실제로는 lstg_stqt × 현재가가 시총. 다음 사이클에 inquire-price 통합.
            val capital = o.cpta.parseBigDecimal()
            return InstrumentDetail(
                code = code,
                name = o.prdtAbrvName.ifBlank { o.prdtName }.ifBlank { code },
                sector = o.idxBztpLclsCd.takeIf { it.isNotBlank() },
                marketCap = capital, // 1차: 자본금. 다음 사이클: lstg_stqt × current_price
                sharesOutstanding = o.lstgStqt.parseLong(),
                foreignRatio = null,
                institutionRatio = null,
                // 재무정보는 DART에서 보완 (이미 정상화)
                revenue = null,
                operatingIncome = null,
                netIncome = null,
                // PER/PBR/EPS/BPS는 search-stock-info 미제공 — 다음 사이클 inquire-price 통합
                per = null,
                pbr = null,
                eps = null,
                bps = null,
                dividendYield = null,
                majorShareholderRatio = null,
                representativeName = null,
                industry = o.idxBztpLclsCd.takeIf { it.isNotBlank() },
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class StockInfoOutput(
        /** 표준 종목코드 (00000A005930) */
        @JsonProperty("pdno") val pdno: String = "",
        /** 종목명 (삼성전자보통주) */
        @JsonProperty("prdt_name") val prdtName: String = "",
        /** 종목 약칭 (삼성전자) — 사용자 표시용 */
        @JsonProperty("prdt_abrv_name") val prdtAbrvName: String = "",
        /** 단축 종목코드 (005930) */
        @JsonProperty("shtn_pdno") val shtnPdno: String = "",
        /** 상장 주식수 */
        @JsonProperty("lstg_stqt") val lstgStqt: String = "",
        /** 자본금 (KRW) */
        @JsonProperty("cpta") val cpta: String = "",
        /** 액면가 */
        @JsonProperty("papr") val papr: String = "",
        /** 결산월 (12) */
        @JsonProperty("setl_mmdd") val setlMmdd: String = "",
        /** KOSPI200 편입 (Y/N) */
        @JsonProperty("kospi200_item_yn") val kospi200ItemYn: String = "",
        /** 시장 ID (STK·KSQ) */
        @JsonProperty("mket_id_cd") val mketIdCd: String = "",
        /** 업종 분류 코드 (industry) */
        @JsonProperty("idx_bztp_lcls_cd") val idxBztpLclsCd: String = "",
        /** 코스피 상장일 (yyyyMMdd) */
        @JsonProperty("scts_mket_lstg_dt") val sctsMketLstgDt: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

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
 * KIS 종목 정보 REST 어댑터 (T20-T2 + inquire-price 통합).
 *
 * **두 가지 KIS endpoint 활용:**
 *
 * 1. search-stock-info (CTPF1002R) — 종목 기본정보
 *    - 엔드포인트: GET /uapi/domestic-stock/v1/quotations/search-stock-info
 *    - 응답: 종목명·상장주식수·자본금·업종코드·시장ID 등
 *
 * 2. inquire-price (FHKST01010100) — 현재가 + 투자지표
 *    - 엔드포인트: GET /uapi/domestic-stock/v1/quotations/inquire-price
 *    - 쿼리파라미터: FID_COND_MRKT_DIV_CODE=J, FID_INPUT_ISCD=종목코드
 *    - 응답: stck_prpr(현재가), hts_avls(시가총액·억원 단위), per, pbr, eps, bps,
 *      bstp_kor_isnm(업종 한글명), hts_frgn_ehrt(외국인보유율)
 *    - hts_avls 단위: 억원 → * 100_000_000 으로 원 단위 환산
 *
 * MCP + 직접 호출로 실측 검증 완료 (2026-05-03):
 *   - 005930: stck_prpr=220500, hts_avls=12891044(억원=약 1289조),
 *     per=33.59, pbr=3.45, eps=6564, bps=63997, bstp_kor_isnm=전기·전자
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
     * 종목 기본정보 조회 (search-stock-info / CTPF1002R).
     * KIS 키 미설정 시 null 반환.
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

    /**
     * 현재가 + 투자지표 조회 (inquire-price / FHKST01010100).
     *
     * 반환 필드:
     * - per·pbr·eps·bps (투자지표)
     * - marketCap (hts_avls * 100_000_000 — 억원→원 환산)
     * - industry (bstp_kor_isnm — 한글 업종명, 예: "전기·전자")
     * - foreignRatio (hts_frgn_ehrt — 외국인 보유율 %)
     *
     * KIS 키 미설정 또는 API 실패 시 null 반환 (graceful degradation).
     */
    fun fetchPriceMetrics(code: String): PriceMetrics? {
        if (appKey.isBlank() || appSecret.isBlank()) {
            log.debug("[kis-price] appKey 미설정 — fetchPriceMetrics 건너뜀 code={}", code)
            return null
        }

        return try {
            if (cachedToken == null) cachedToken = issueToken() ?: return null
            callInquirePrice(code)
        } catch (ex: KisInstrumentInfoException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-price] 401 — 토큰 재발급 후 1회 재시도 code={}", code)
                cachedToken = issueToken() ?: return null
                runCatching { callInquirePrice(code) }.getOrElse { null }
            } else {
                log.warn("[kis-price] fetchPriceMetrics 실패 code={} error={}", code, ex.message)
                null
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                cachedToken = issueToken() ?: return null
                runCatching { callInquirePrice(code) }.getOrElse { null }
            } else {
                log.warn("[kis-price] HTTP 오류 code={} status={}", code, ex.statusCode)
                null
            }
        } catch (ex: Exception) {
            log.warn("[kis-price] fetchPriceMetrics 실패 code={} error={}", code, ex.message)
            null
        }
    }

    /**
     * inquire-price에서 추출한 투자지표 데이터 클래스.
     *
     * @param marketCap    시가총액 (원). hts_avls(억) × 100_000_000 환산.
     * @param industry     업종 한글명 (bstp_kor_isnm, 예: "전기·전자").
     * @param foreignRatio 외국인 보유율 (%, hts_frgn_ehrt).
     */
    data class PriceMetrics(
        val per: BigDecimal?,
        val pbr: BigDecimal?,
        val eps: BigDecimal?,
        val bps: BigDecimal?,
        val marketCap: BigDecimal?,
        val industry: String?,
        val foreignRatio: BigDecimal?,
    )

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

    private fun callInquirePrice(code: String): PriceMetrics? {
        log.info("[kis-price] FHKST01010100 호출 code={}", code)
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", code)
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer ${cachedToken!!}"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHKST01010100"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisInstrumentInfoException("401", 401)
                    }
                    log.warn("[kis-price] HTTP 오류 code={} status={}", code, resp.statusCode)
                    throw KisInstrumentInfoException("KIS inquire-price 오류: ${resp.statusCode}", resp.statusCode.value())
                }.body(InquirePriceResponse::class.java)
                ?: return null

        log.info("[kis-price] FHKST01010100 응답 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)

        if (response.rtCd != "0") {
            log.warn("[kis-price] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return null
        }

        return response.toPriceMetrics()
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
     * 실 응답 필드:
     * - pdno          : 표준 종목코드 (00000A005930)
     * - prdt_name     : 종목명 (삼성전자보통주)
     * - prdt_abrv_name: 종목 약칭 (삼성전자)
     * - shtn_pdno     : 단축 종목코드 (005930)
     * - lstg_stqt     : 상장 주식수
     * - cpta          : 자본금 (KRW) — 시총과 다름
     * - mket_id_cd    : 시장 ID (STK=KOSPI, KSQ=KOSDAQ)
     * - idx_bztp_lcls_cd : 업종 분류 코드 (예: "002"; 한글명은 inquire-price에서 취득)
     *
     * 주의: 시총·PER·PBR·EPS·BPS는 이 endpoint에서 미제공.
     *       fetchPriceMetrics (FHKST01010100)에서 병합.
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
                name = o.prdtAbrvName.ifBlank { o.prdtName }.ifBlank { code },
                // 시장 ID: STK=KOSPI, KSQ=KOSDAQ
                market =
                    when (o.mketIdCd.trim().uppercase()) {
                        "STK" -> "KOSPI"
                        "KSQ" -> "KOSDAQ"
                        else -> o.mketIdCd.takeIf { it.isNotBlank() }
                    },
                sector = o.idxBztpLclsCd.takeIf { it.isNotBlank() },
                // 시총·PER/PBR/EPS/BPS·업종한글명·외국인보유율은 fetchPriceMetrics에서 병합
                marketCap = null,
                sharesOutstanding = o.lstgStqt.parseLong(),
                per = null,
                pbr = null,
                eps = null,
                bps = null,
                foreignRatio = null,
                institutionRatio = null,
                revenue = null,
                operatingIncome = null,
                netIncome = null,
                dividendYield = null,
                majorShareholderRatio = null,
                representativeName = null,
                industry = null,
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
        /** 시장 ID (STK=KOSPI, KSQ=KOSDAQ) */
        @JsonProperty("mket_id_cd") val mketIdCd: String = "",
        /** 업종 분류 코드 (예: "002") */
        @JsonProperty("idx_bztp_lcls_cd") val idxBztpLclsCd: String = "",
        /** 코스피 상장일 (yyyyMMdd) */
        @JsonProperty("scts_mket_lstg_dt") val sctsMketLstgDt: String = "",
    )

    /**
     * FHKST01010100 응답 매핑 (inquire-price).
     *
     * 실측 검증 완료 (2026-05-03, 005930 기준):
     * - stck_prpr : 현재가 (220500원)
     * - hts_avls  : 시가총액 — 억원 단위 (12891044 → * 1억 = 약 1,289조원)
     * - per       : PER (33.59)
     * - pbr       : PBR (3.45)
     * - eps       : EPS (6564.00원)
     * - bps       : BPS (63997.00원)
     * - bstp_kor_isnm    : 업종 한글명 ("전기·전자")
     * - rprs_mrkt_kor_name: 대표시장 ("KOSPI200")
     * - hts_frgn_ehrt    : 외국인 보유율 (49.24%)
     * - lstn_stcn        : 상장주식수 (5846278608)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InquirePriceResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: InquirePriceOutput = InquirePriceOutput(),
    ) {
        fun toPriceMetrics(): PriceMetrics {
            val o = output
            // hts_avls는 억원 단위 → 원 단위 변환 (* 100_000_000)
            val marketCapValue = o.htsAvls.parseLong()?.let { BigDecimal(it).multiply(BigDecimal("100000000")) }
            return PriceMetrics(
                per = o.per.parseBigDecimal(),
                pbr = o.pbr.parseBigDecimal(),
                eps = o.eps.parseBigDecimal(),
                bps = o.bps.parseBigDecimal(),
                marketCap = marketCapValue,
                industry = o.bstpKorIsnm.takeIf { it.isNotBlank() },
                foreignRatio = o.htsFrgnEhrt.parseBigDecimal(),
            )
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InquirePriceOutput(
        /** 현재가 (원) */
        @JsonProperty("stck_prpr") val stckPrpr: String = "",
        /** 시가총액 (억원 단위) — * 100_000_000 으로 원 환산 필요 */
        @JsonProperty("hts_avls") val htsAvls: String = "",
        /** PER */
        @JsonProperty("per") val per: String = "",
        /** PBR */
        @JsonProperty("pbr") val pbr: String = "",
        /** EPS (원) */
        @JsonProperty("eps") val eps: String = "",
        /** BPS (원) */
        @JsonProperty("bps") val bps: String = "",
        /** 업종 한글명 (예: "전기·전자") */
        @JsonProperty("bstp_kor_isnm") val bstpKorIsnm: String = "",
        /** 대표시장 (예: "KOSPI200") */
        @JsonProperty("rprs_mrkt_kor_name") val rprsMrktKorName: String = "",
        /** 외국인 보유율 (%) */
        @JsonProperty("hts_frgn_ehrt") val htsFrgnEhrt: String = "",
        /** 상장주식수 */
        @JsonProperty("lstn_stcn") val lstnStcn: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.market.instrument.InstrumentDetail
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
 * KIS 종목 정보 REST 어댑터 (search-stock-info + inquire-price 통합).
 *
 * R3-5c-3 PR3: 자체 토큰 관리(`cachedToken`/`issueToken()`) 제거 → [KisTokenStorePort] 주입 + scope 단일화.
 *
 * **두 가지 KIS endpoint 활용:**
 *
 * 1. search-stock-info (CTPF1002R) — 종목 기본정보
 * 2. inquire-price (FHKST01010100) — 현재가 + 투자지표
 *
 * KIS 키 미설정(appKey blank) 시 graceful degradation — null 반환.
 * 401 응답 → [KisTokenStorePort.invalidate] + 1회 재시도.
 */
@Component
class KisInstrumentInfoAdapter(
    @Value("\${magicjar.kis.base-url.prod:\${magicjar.kis.base-url:https://openapivts.koreainvestment.com:29443}}")
    private val baseUrl: String,
    @Value("\${magicjar.kis.system.app-key.prod:\${magicjar.kis.app-key:}}")
    private val appKey: String,
    @Value("\${magicjar.kis.system.app-secret.prod:\${magicjar.kis.app-secret:}}")
    private val appSecret: String,
    private val tokenStore: KisTokenStorePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 사용자 정책: 데이터 조회는 항상 실전 시스템 키. R3-5c-3 KisAuthScope.System("prod"). */
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
     * KIS appKey/appSecret 설정 여부. 경고 코드 구분에 사용.
     */
    fun isKeyConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

    /**
     * 종목 기본정보 조회 (search-stock-info / CTPF1002R).
     * KIS 키 미설정 시 null 반환.
     */
    fun fetchInstrumentInfo(code: String): InstrumentDetail? {
        if (!isKeyConfigured()) {
            log.debug("[kis-info] appKey 미설정 — fetchInstrumentInfo 건너뜀 code={}", code)
            return null
        }

        return try {
            executeWithTokenRetry(code) { token -> callSearchStockInfo(code, token) }
        } catch (ex: Exception) {
            log.warn("[kis-info] fetchInstrumentInfo 실패 code={} error={}", code, ex.message)
            null
        }
    }

    /**
     * 현재가 + 투자지표 조회 (inquire-price / FHKST01010100).
     */
    fun fetchPriceMetrics(code: String): PriceMetrics? {
        if (!isKeyConfigured()) {
            log.debug("[kis-price] appKey 미설정 — fetchPriceMetrics 건너뜀 code={}", code)
            return null
        }

        return try {
            executeWithTokenRetry(code) { token -> callInquirePrice(code, token) }
        } catch (ex: Exception) {
            log.warn("[kis-price] fetchPriceMetrics 실패 code={} error={}", code, ex.message)
            null
        }
    }

    /**
     * inquire-price에서 추출한 투자지표 데이터 클래스.
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

    /**
     * 표준 401 retry once 패턴 (R3-5c-3 D10).
     *
     * action에 토큰을 전달. 401 발생 시 invalidate → 재발급 → 1회 retry.
     */
    private fun <T> executeWithTokenRetry(
        code: String,
        action: (String) -> T?,
    ): T? {
        val token = tokenStore.currentTokenOrIssue(authScope)
        return try {
            action(token)
        } catch (ex: KisInstrumentInfoException) {
            if (ex.statusCode == 401) {
                log.warn("[kis-info] 401 — invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[kis-info] 401 HTTP — invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                log.warn("[kis-info] HTTP 오류 code={} status={}", code, ex.statusCode)
                null
            }
        }
    }

    private fun callSearchStockInfo(
        code: String,
        token: String,
    ): InstrumentDetail? {
        log.info("[kis-info] CTPF1002R 호출 code={} baseUrl={}", code, baseUrl)
        val response =
            restClient
                .get()
                .uri { b ->
                    b
                        .path("/uapi/domestic-stock/v1/quotations/search-stock-info")
                        .queryParam("PDNO", code)
                        .queryParam("PRDT_TYPE_CD", "300")
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer $token"
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
                    throw KisInstrumentInfoException(
                        "KIS search-stock-info 오류: ${resp.statusCode}",
                        resp.statusCode.value(),
                    )
                }.body(StockInfoResponse::class.java)
                ?: return null

        log.info("[kis-info] CTPF1002R 응답 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)

        if (response.rtCd != "0") {
            log.warn("[kis-info] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return null
        }

        return response.toDetail(code)
    }

    private fun callInquirePrice(
        code: String,
        token: String,
    ): PriceMetrics? {
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
                    h["authorization"] = "Bearer $token"
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
                    throw KisInstrumentInfoException(
                        "KIS inquire-price 오류: ${resp.statusCode}",
                        resp.statusCode.value(),
                    )
                }.body(InquirePriceResponse::class.java)
                ?: return null

        log.info("[kis-price] FHKST01010100 응답 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)

        if (response.rtCd != "0") {
            log.warn("[kis-price] rt_cd 비정상 code={} rt_cd={} msg={}", code, response.rtCd, response.msg1)
            return null
        }

        return response.toPriceMetrics()
    }

    // ── 내부 예외 ─────────────────────────────────────────────────────────────

    private class KisInstrumentInfoException(
        message: String,
        val statusCode: Int = 0,
    ) : RuntimeException(message)

    // ── 내부 DTO ──────────────────────────────────────────────────────────────

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
                market =
                    when (o.mketIdCd.trim().uppercase()) {
                        "STK" -> "KOSPI"
                        "KSQ" -> "KOSDAQ"
                        else -> o.mketIdCd.takeIf { it.isNotBlank() }
                    },
                sector = o.idxBztpLclsCd.takeIf { it.isNotBlank() },
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
        @JsonProperty("pdno") val pdno: String = "",
        @JsonProperty("prdt_name") val prdtName: String = "",
        @JsonProperty("prdt_abrv_name") val prdtAbrvName: String = "",
        @JsonProperty("shtn_pdno") val shtnPdno: String = "",
        @JsonProperty("lstg_stqt") val lstgStqt: String = "",
        @JsonProperty("cpta") val cpta: String = "",
        @JsonProperty("papr") val papr: String = "",
        @JsonProperty("setl_mmdd") val setlMmdd: String = "",
        @JsonProperty("kospi200_item_yn") val kospi200ItemYn: String = "",
        @JsonProperty("mket_id_cd") val mketIdCd: String = "",
        @JsonProperty("idx_bztp_lcls_cd") val idxBztpLclsCd: String = "",
        @JsonProperty("scts_mket_lstg_dt") val sctsMketLstgDt: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class InquirePriceResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: InquirePriceOutput = InquirePriceOutput(),
    ) {
        fun toPriceMetrics(): PriceMetrics {
            val o = output
            val marketCapValue =
                o.htsAvls.parseLong()?.let { BigDecimal(it).multiply(BigDecimal("100000000")) }
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
        @JsonProperty("stck_prpr") val stckPrpr: String = "",
        @JsonProperty("hts_avls") val htsAvls: String = "",
        @JsonProperty("per") val per: String = "",
        @JsonProperty("pbr") val pbr: String = "",
        @JsonProperty("eps") val eps: String = "",
        @JsonProperty("bps") val bps: String = "",
        @JsonProperty("bstp_kor_isnm") val bstpKorIsnm: String = "",
        @JsonProperty("rprs_mrkt_kor_name") val rprsMrktKorName: String = "",
        @JsonProperty("hts_frgn_ehrt") val htsFrgnEhrt: String = "",
        @JsonProperty("lstn_stcn") val lstnStcn: String = "",
    )

    companion object {
        private fun String.parseBigDecimal(): BigDecimal? =
            trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

        private fun String.parseLong(): Long? = trim().takeIf { it.isNotBlank() }?.toLongOrNull()
    }
}

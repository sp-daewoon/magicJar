package com.mj.batch.infrastructure.kis.corp_action

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.batch.infrastructure.kis.KisApiException
import com.mj.batch.infrastructure.kis.KisRestProperties
import com.mj.domain.market.corp_action.CorpAction
import com.mj.domain.market.corp_action.CorpActionSource
import com.mj.domain.market.corp_action.port.KisCorpActionPort
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import org.springframework.web.client.RestClientResponseException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter

// -------------------------------------------------------------------------
// 파일 레벨 파싱 유틸
// -------------------------------------------------------------------------

private val KSD_DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")

private fun String.parseKsdDate(): LocalDate? =
    if (length >= 8) {
        runCatching { LocalDate.parse(substring(0, 8), KSD_DATE_FMT) }.getOrNull()
    } else {
        null
    }

private fun String.parseDecimalSafe(): BigDecimal? = trim().takeIf { it.isNotBlank() }?.let { runCatching { BigDecimal(it) }.getOrNull() }

/**
 * dev·prd 프로파일용 KisCorpActionPort 구현체.
 *
 * KIS 예탁원정보 API를 통해 Corp Action 이력을 조회한다.
 *
 * 채택 API (KIS GitHub open-trading-api examples_user 검색 결과):
 * - 액면분할·교체: GET /uapi/domestic-stock/v1/ksdinfo/rev-split      trId=HHKDB669105C0
 * - 무상증자:      GET /uapi/domestic-stock/v1/ksdinfo/bonus-issue     trId=HHKDB669101C0
 * - 유상증자:      GET /uapi/domestic-stock/v1/ksdinfo/paidin-capin    trId=HHKDB669100C0
 * - 배당:          GET /uapi/domestic-stock/v1/ksdinfo/dividend        trId=HHKDB669102C0
 * - 합병·분할:     GET /uapi/domestic-stock/v1/ksdinfo/merger-split    trId=HHKDB669104C0
 *
 * 공통 파라미터:
 * - CTS: 연속 조회 키 (공백 = 최초 조회)
 * - F_DT: 조회 시작일 (yyyyMMdd)
 * - T_DT: 조회 종료일 (yyyyMMdd)
 * - SHT_CD: 종목코드 (공백 = 전체)
 *
 * 레이트리밋: KIS 초당 20건 → 호출 간 50ms sleep
 * 재시도: 401 시 토큰 갱신 후 1회 재시도
 *
 * @see StubKisCorpActionAdapter stub-kis·mock 프로파일 어댑터
 */
@Component
@Profile("dev | prd")
class RealKisCorpActionAdapter(
    private val props: KisRestProperties,
    private val tokenStore: KisTokenStorePort,
) : KisCorpActionPort {
    private val log = LoggerFactory.getLogger(RealKisCorpActionAdapter::class.java)
    private val yyyymmdd = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** R3-5c-3 PR4: KisCorpActionTokenHolder 폐기 → port 직접 주입. */
    private val authScope: KisAuthScope = KisAuthScope.System("prod")

    /** 호출 시점 토큰. executeWithTokenRefresh 내부에서 갱신. applyKisHeaders가 참조. */
    @Volatile
    private var currentToken: String = ""

    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()

    // -------------------------------------------------------------------------
    // KisCorpActionPort 구현
    // -------------------------------------------------------------------------

    override fun fetchSplits(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.Split> {
        log.debug("[real-kis] fetchSplits code={} from={} to={}", instrumentCode, from, to)
        return executeWithTokenRefresh {
            val response =
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/ksdinfo/rev-split")
                            .queryParam("SHT_CD", instrumentCode)
                            .queryParam("CTS", " ")
                            .queryParam("F_DT", from.format(yyyymmdd))
                            .queryParam("T_DT", to.format(yyyymmdd))
                            .queryParam("MARKET_GB", "0")
                            .build()
                    }.headers { h -> applyKisHeaders(h, "HHKDB669105C0") }
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) throw KisApiException("401", rtCd = "401")
                        throw KisApiException("fetchSplits HTTP ${resp.statusCode}", rtCd = resp.statusCode.value().toString())
                    }.body(KsdInfoRevSplitResponse::class.java)
                    ?: throw KisApiException("fetchSplits null response for code=$instrumentCode")
            response.assertSuccess()
            response.toSplits(instrumentCode)
        }
    }

    override fun fetchBonusIssues(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.BonusIssue> {
        log.debug("[real-kis] fetchBonusIssues code={} from={} to={}", instrumentCode, from, to)
        return executeWithTokenRefresh {
            val response =
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/ksdinfo/bonus-issue")
                            .queryParam("CTS", " ")
                            .queryParam("F_DT", from.format(yyyymmdd))
                            .queryParam("T_DT", to.format(yyyymmdd))
                            .queryParam("SHT_CD", instrumentCode)
                            .build()
                    }.headers { h -> applyKisHeaders(h, "HHKDB669101C0") }
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) throw KisApiException("401", rtCd = "401")
                        throw KisApiException("fetchBonusIssues HTTP ${resp.statusCode}", rtCd = resp.statusCode.value().toString())
                    }.body(KsdInfoBonusIssueResponse::class.java)
                    ?: throw KisApiException("fetchBonusIssues null response for code=$instrumentCode")
            response.assertSuccess()
            response.toBonusIssues(instrumentCode)
        }
    }

    override fun fetchRightsIssues(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.RightsIssue> {
        log.debug("[real-kis] fetchRightsIssues code={} from={} to={}", instrumentCode, from, to)
        return executeWithTokenRefresh {
            val response =
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/ksdinfo/paidin-capin")
                            .queryParam("CTS", " ")
                            .queryParam("GB1", "2")
                            .queryParam("F_DT", from.format(yyyymmdd))
                            .queryParam("T_DT", to.format(yyyymmdd))
                            .queryParam("SHT_CD", instrumentCode)
                            .build()
                    }.headers { h -> applyKisHeaders(h, "HHKDB669100C0") }
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) throw KisApiException("401", rtCd = "401")
                        throw KisApiException("fetchRightsIssues HTTP ${resp.statusCode}", rtCd = resp.statusCode.value().toString())
                    }.body(KsdInfoPaidinCapinResponse::class.java)
                    ?: throw KisApiException("fetchRightsIssues null response for code=$instrumentCode")
            response.assertSuccess()
            response.toRightsIssues(instrumentCode)
        }
    }

    override fun fetchDividends(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction.Dividend> {
        log.debug("[real-kis] fetchDividends code={} from={} to={}", instrumentCode, from, to)
        return executeWithTokenRefresh {
            val response =
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/ksdinfo/dividend")
                            .queryParam("CTS", " ")
                            .queryParam("GB1", "0")
                            .queryParam("F_DT", from.format(yyyymmdd))
                            .queryParam("T_DT", to.format(yyyymmdd))
                            .queryParam("SHT_CD", instrumentCode)
                            .queryParam("HIGH_GB", " ")
                            .build()
                    }.headers { h -> applyKisHeaders(h, "HHKDB669102C0") }
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) throw KisApiException("401", rtCd = "401")
                        throw KisApiException("fetchDividends HTTP ${resp.statusCode}", rtCd = resp.statusCode.value().toString())
                    }.body(KsdInfoDividendResponse::class.java)
                    ?: throw KisApiException("fetchDividends null response for code=$instrumentCode")
            response.assertSuccess()
            response.toDividends(instrumentCode)
        }
    }

    override fun fetchMergersAndSpinoffs(
        instrumentCode: String,
        from: LocalDate,
        to: LocalDate,
    ): List<CorpAction> {
        log.debug("[real-kis] fetchMergersAndSpinoffs code={} from={} to={}", instrumentCode, from, to)
        return executeWithTokenRefresh {
            val response =
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/ksdinfo/merger-split")
                            .queryParam("CTS", " ")
                            .queryParam("F_DT", from.format(yyyymmdd))
                            .queryParam("T_DT", to.format(yyyymmdd))
                            .queryParam("SHT_CD", instrumentCode)
                            .build()
                    }.headers { h -> applyKisHeaders(h, "HHKDB669104C0") }
                    .retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) throw KisApiException("401", rtCd = "401")
                        throw KisApiException("fetchMergersAndSpinoffs HTTP ${resp.statusCode}", rtCd = resp.statusCode.value().toString())
                    }.body(KsdInfoMergerSplitResponse::class.java)
                    ?: throw KisApiException("fetchMergersAndSpinoffs null response for code=$instrumentCode")
            response.assertSuccess()
            response.toCorpActions(instrumentCode)
        }
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    private fun applyKisHeaders(
        headers: org.springframework.http.HttpHeaders,
        trId: String,
    ) {
        headers["authorization"] = "Bearer $currentToken"
        headers["appkey"] = props.appKey
        headers["appsecret"] = props.appSecret
        headers["tr_id"] = trId
    }

    /**
     * R3-5c-3 PR4: KisCorpActionTokenHolder 폐기 → [KisTokenStorePort] 위임 + 표준 401 retry once.
     * `currentToken` 필드를 통해 같은 호출 내 헤더가 일관되게 사용되도록 한다.
     */
    private fun <T> executeWithTokenRefresh(action: () -> T): T {
        currentToken = tokenStore.currentTokenOrIssue(authScope)
        return try {
            action()
        } catch (ex: KisApiException) {
            if (ex.rtCd == "401") {
                log.warn("[real-kis] 401 — invalidate corp action token + retry once")
                tokenStore.invalidate(authScope)
                currentToken = tokenStore.currentTokenOrIssue(authScope)
                action()
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[real-kis] 401 HTTP — invalidate corp action token + retry once")
                tokenStore.invalidate(authScope)
                currentToken = tokenStore.currentTokenOrIssue(authScope)
                action()
            } else {
                throw KisApiException(
                    "KIS Corp Action HTTP error: ${ex.statusCode}",
                    rtCd = ex.statusCode.value().toString(),
                )
            }
        }
    }

    // -------------------------------------------------------------------------
    // KSD 응답 DTO
    // -------------------------------------------------------------------------

    private interface KsdInfoResponse {
        val rtCd: String
        val msgCd: String
        val msg1: String

        fun assertSuccess() {
            if (rtCd != "0") {
                throw KisApiException(
                    "[real-kis] KSD Corp Action error: rt_cd=$rtCd msg_cd=$msgCd msg=$msg1",
                    msgCd = msgCd,
                    rtCd = rtCd,
                )
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KsdInfoRevSplitResponse(
        @JsonProperty("rt_cd") override val rtCd: String = "",
        @JsonProperty("msg_cd") override val msgCd: String = "",
        @JsonProperty("msg1") override val msg1: String = "",
        @JsonProperty("output1") val output1: List<RevSplitRow> = emptyList(),
    ) : KsdInfoResponse {
        fun toSplits(instrumentCode: String): List<CorpAction.Split> =
            output1
                .filter { it.sht_cd.trim() == instrumentCode || it.sht_cd.isBlank() }
                .mapNotNull { row ->
                    val exDate = row.ex_dt.parseKsdDate() ?: return@mapNotNull null
                    val ratio = row.ratio.parseDecimalSafe() ?: return@mapNotNull null
                    CorpAction.Split(
                        instrumentCode = row.sht_cd.trim().ifBlank { instrumentCode },
                        exDate = exDate,
                        source = CorpActionSource.KIS,
                        ratio = ratio,
                    )
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class RevSplitRow(
        @JsonProperty("sht_cd") val sht_cd: String = "",
        @JsonProperty("ex_dt") val ex_dt: String = "",
        @JsonProperty("bfac_nmvl") val ratio: String = "1",
        @JsonProperty("bfor_par_prc") val bfor_par_prc: String = "",
        @JsonProperty("aftr_par_prc") val aftr_par_prc: String = "",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KsdInfoBonusIssueResponse(
        @JsonProperty("rt_cd") override val rtCd: String = "",
        @JsonProperty("msg_cd") override val msgCd: String = "",
        @JsonProperty("msg1") override val msg1: String = "",
        @JsonProperty("output1") val output1: List<BonusIssueRow> = emptyList(),
    ) : KsdInfoResponse {
        fun toBonusIssues(instrumentCode: String): List<CorpAction.BonusIssue> =
            output1
                .filter { it.sht_cd.trim() == instrumentCode || it.sht_cd.isBlank() }
                .mapNotNull { row ->
                    val exDate = row.ex_dt.parseKsdDate() ?: return@mapNotNull null
                    val ratio = row.bonus_ratio.parseDecimalSafe() ?: return@mapNotNull null
                    CorpAction.BonusIssue(
                        instrumentCode = row.sht_cd.trim().ifBlank { instrumentCode },
                        exDate = exDate,
                        source = CorpActionSource.KIS,
                        ratio = ratio,
                    )
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class BonusIssueRow(
        @JsonProperty("sht_cd") val sht_cd: String = "",
        @JsonProperty("ex_dt") val ex_dt: String = "",
        @JsonProperty("bonus_rt") val bonus_ratio: String = "0",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KsdInfoPaidinCapinResponse(
        @JsonProperty("rt_cd") override val rtCd: String = "",
        @JsonProperty("msg_cd") override val msgCd: String = "",
        @JsonProperty("msg1") override val msg1: String = "",
        @JsonProperty("output1") val output1: List<PaidinCapinRow> = emptyList(),
    ) : KsdInfoResponse {
        fun toRightsIssues(instrumentCode: String): List<CorpAction.RightsIssue> =
            output1
                .filter { it.sht_cd.trim() == instrumentCode || it.sht_cd.isBlank() }
                .mapNotNull { row ->
                    val exDate = row.ex_dt.parseKsdDate() ?: return@mapNotNull null
                    val ratio = row.rights_ratio.parseDecimalSafe() ?: BigDecimal.ZERO
                    val subPrice = row.sub_price.parseDecimalSafe() ?: BigDecimal.ZERO
                    CorpAction.RightsIssue(
                        instrumentCode = row.sht_cd.trim().ifBlank { instrumentCode },
                        exDate = exDate,
                        source = CorpActionSource.KIS,
                        ratio = ratio,
                        subscriptionPrice = subPrice,
                    )
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class PaidinCapinRow(
        @JsonProperty("sht_cd") val sht_cd: String = "",
        @JsonProperty("ex_dt") val ex_dt: String = "",
        @JsonProperty("issue_rt") val rights_ratio: String = "0",
        @JsonProperty("issue_prc") val sub_price: String = "0",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KsdInfoDividendResponse(
        @JsonProperty("rt_cd") override val rtCd: String = "",
        @JsonProperty("msg_cd") override val msgCd: String = "",
        @JsonProperty("msg1") override val msg1: String = "",
        @JsonProperty("output1") val output1: List<DividendRow> = emptyList(),
    ) : KsdInfoResponse {
        fun toDividends(instrumentCode: String): List<CorpAction.Dividend> =
            output1
                .filter { it.sht_cd.trim() == instrumentCode || it.sht_cd.isBlank() }
                .mapNotNull { row ->
                    val exDate = row.ex_dt.parseKsdDate() ?: return@mapNotNull null
                    val cashAmount = row.cash_div.parseDecimalSafe() ?: BigDecimal.ZERO
                    val stockRatio = row.stock_div_rt.parseDecimalSafe() ?: BigDecimal.ZERO
                    CorpAction.Dividend(
                        instrumentCode = row.sht_cd.trim().ifBlank { instrumentCode },
                        exDate = exDate,
                        source = CorpActionSource.KIS,
                        cashAmount = cashAmount,
                        stockRatio = stockRatio,
                    )
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DividendRow(
        @JsonProperty("sht_cd") val sht_cd: String = "",
        @JsonProperty("ex_dt") val ex_dt: String = "",
        @JsonProperty("cash_div") val cash_div: String = "0",
        @JsonProperty("stk_div_rt") val stock_div_rt: String = "0",
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class KsdInfoMergerSplitResponse(
        @JsonProperty("rt_cd") override val rtCd: String = "",
        @JsonProperty("msg_cd") override val msgCd: String = "",
        @JsonProperty("msg1") override val msg1: String = "",
        @JsonProperty("output1") val output1: List<MergerSplitRow> = emptyList(),
    ) : KsdInfoResponse {
        fun toCorpActions(instrumentCode: String): List<CorpAction> =
            output1
                .filter { it.sht_cd.trim() == instrumentCode || it.sht_cd.isBlank() }
                .mapNotNull { row ->
                    val exDate = row.ex_dt.parseKsdDate() ?: return@mapNotNull null
                    val code = row.sht_cd.trim().ifBlank { instrumentCode }
                    when {
                        row.isMerger() ->
                            CorpAction.Merger(
                                instrumentCode = code,
                                exDate = exDate,
                                source = CorpActionSource.KIS,
                                mergeRatio = row.ratio.parseDecimalSafe() ?: BigDecimal.ONE,
                            )
                        row.isSpinoff() ->
                            CorpAction.Spinoff(
                                instrumentCode = code,
                                exDate = exDate,
                                source = CorpActionSource.KIS,
                                splitRatio = row.ratio.parseDecimalSafe() ?: BigDecimal.ZERO,
                            )
                        else -> null
                    }
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MergerSplitRow(
        @JsonProperty("sht_cd") val sht_cd: String = "",
        @JsonProperty("ex_dt") val ex_dt: String = "",
        @JsonProperty("div_code") val div_code: String = "",
        @JsonProperty("mrg_rt") val ratio: String = "1",
    ) {
        fun isMerger(): Boolean = div_code.trim() in listOf("1", "M", "합병")

        fun isSpinoff(): Boolean = div_code.trim() in listOf("2", "S", "분할")
    }
}

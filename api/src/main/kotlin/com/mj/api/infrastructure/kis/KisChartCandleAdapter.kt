package com.mj.api.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
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
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS inquire-daily-itemchartprice 일봉(D)/주봉(W)/월봉(M) 직접 fetch 어댑터.
 * ADR 0050 R3-4 (W/M) + R3-5a (D1 추가, DB fallback 제거).
 *
 * 지원 interval:
 *   - [Interval.D1]    → FID_PERIOD_DIV_CODE=D  (일봉, 기본 200봉 / 2페이지)
 *   - [Interval.WEEK]  → FID_PERIOD_DIV_CODE=W  (주봉, 최대 200봉 / 2페이지)
 *   - [Interval.MONTH] → FID_PERIOD_DIV_CODE=M  (월봉, 최대 60봉  / 1페이지)
 *
 * 사용자 차트 화면 전용 (ADR 0050 §2-A). batch [RealKisRestAdapter]는
 * 룰 엔진/백테스트 backfill 용도로 별도 유지. M1(분봉)은 R3-5b에서 yfinance direct.
 *
 * KIS 키(appKey/appSecret) 미설정 시 빈 리스트 반환 (graceful degradation).
 * 401 응답 → 토큰 무효화 + 1회 재발급 재시도.
 *
 * 페이징: 1회 호출당 최대 [KIS_PAGE_LIMIT]=100봉. 안전 한도 [MAX_PAGES]=4.
 *   - WEEK  기본 200봉 → 2페이지 필요
 *   - MONTH 기본  60봉 → 1페이지 충분 (100 미만이면 종료)
 */
@Component
class KisChartCandleAdapter(
    @Value("\${spring.kis.base-url:https://openapi.koreainvestment.com:9443}")
    private val baseUrl: String,
    @Value("\${spring.kis.app-key:}")
    private val appKey: String,
    @Value("\${spring.kis.app-secret:}")
    private val appSecret: String,
    private val tokenStore: KisTokenStorePort,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")
    private val yyyymmdd: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

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
     * KIS 키가 설정되어 있는지 여부.
     * false 시 [fetchCandles] 가 빈 리스트를 반환한다.
     */
    fun isKeyConfigured(): Boolean = appKey.isNotBlank() && appSecret.isNotBlank()

    /**
     * KIS inquire-daily-itemchartprice 호출 — WEEK/MONTH 주봉·월봉.
     *
     * @param code     KRX 종목코드 (예: "005930")
     * @param interval [Interval.WEEK] 또는 [Interval.MONTH]. D1·M1은 지원하지 않으며 빈 리스트 반환.
     * @param from     조회 시작일 (inclusive)
     * @param to       조회 종료일 (inclusive)
     * @return Candle 목록 (openTime ASC 정렬, 중복 제거). KIS 키 미설정 시 빈 리스트.
     */
    fun fetchCandles(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        if (!isKeyConfigured()) {
            log.debug("[kis-chart] KIS 키 미설정 — fetchCandles 건너뜀 code={} interval={}", code, interval)
            return emptyList()
        }
        if (interval == Interval.M1) {
            log.warn("[kis-chart] M1(분봉) 간격은 지원하지 않음 — code={} interval={}", code, interval)
            return emptyList()
        }
        if (from.isAfter(to)) return emptyList()

        val periodDivCode = intervalToPeriodDivCode(interval)

        val accumulator = mutableListOf<Candle>()
        var cursor = to

        for (page in 0 until MAX_PAGES) {
            if (cursor.isBefore(from)) break

            val pageCandles =
                try {
                    fetchPageWithTokenRefresh(code, periodDivCode, interval, from, cursor)
                } catch (ex: KisChartException) {
                    log.warn("[kis-chart] fetchCandles 실패 code={} interval={} error={}", code, interval, ex.message)
                    break
                }

            if (pageCandles.isEmpty()) break
            accumulator.addAll(pageCandles)

            if (pageCandles.size < KIS_PAGE_LIMIT) break

            val oldestDate =
                pageCandles
                    .minBy { it.openTime }
                    .openTime
                    .atZone(zone)
                    .toLocalDate()
            if (!oldestDate.isAfter(from)) break
            cursor = oldestDate.minusDays(1)
        }

        return accumulator.distinctBy { it.openTime }.sortedBy { it.openTime }
    }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    private fun fetchPageWithTokenRefresh(
        code: String,
        periodDivCode: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> =
        try {
            callOnePage(code, periodDivCode, interval, from, to)
        } catch (ex: KisChartException) {
            if (ex.statusCode == 401 || ex.rtCd == "401") {
                log.warn("[kis-chart] 401 — 토큰 invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                callOnePage(code, periodDivCode, interval, from, to)
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[kis-chart] 401 HTTP — 토큰 invalidate + 1회 재시도 code={}", code)
                tokenStore.invalidate(authScope)
                callOnePage(code, periodDivCode, interval, from, to)
            } else {
                throw KisChartException(
                    "KIS HTTP error ${ex.statusCode} code=$code",
                    statusCode = ex.statusCode.value(),
                )
            }
        }

    private fun callOnePage(
        code: String,
        periodDivCode: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        log.debug(
            "[kis-chart] inquire-daily-itemchartprice code={} period={} from={} to={}",
            code,
            periodDivCode,
            from,
            to,
        )

        val response =
            restClient
                .get()
                .uri { builder ->
                    builder
                        .path("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")
                        .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                        .queryParam("FID_INPUT_ISCD", code)
                        .queryParam("FID_INPUT_DATE_1", from.format(yyyymmdd))
                        .queryParam("FID_INPUT_DATE_2", to.format(yyyymmdd))
                        .queryParam("FID_PERIOD_DIV_CODE", periodDivCode)
                        .queryParam("FID_ORG_ADJ_PRC", "0")
                        .build()
                }.headers { h ->
                    h["authorization"] = "Bearer ${tokenStore.currentTokenOrIssue(authScope)}"
                    h["appkey"] = appKey
                    h["appsecret"] = appSecret
                    h["tr_id"] = "FHKST03010100"
                    h["custtype"] = "P"
                }.retrieve()
                .onStatus(HttpStatusCode::isError) { _, resp ->
                    if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                        throw KisChartException("401 Unauthorized", rtCd = "401", statusCode = 401)
                    }
                    throw KisChartException(
                        "KIS inquire-daily-itemchartprice HTTP error: ${resp.statusCode} code=$code",
                        statusCode = resp.statusCode.value(),
                    )
                }.body(DailyCandleResponse::class.java)
                ?: throw KisChartException("[kis-chart] null response code=$code")

        response.assertSuccess()
        return response.toDomain(code, interval, zone, yyyymmdd)
    }

    private fun intervalToPeriodDivCode(interval: Interval): String =
        when (interval) {
            Interval.D1 -> "D"
            Interval.WEEK -> "W"
            Interval.MONTH -> "M"
            else -> throw KisChartException("Unsupported interval for KisChartCandleAdapter: $interval")
        }

    // -------------------------------------------------------------------------
    // 내부 예외
    // -------------------------------------------------------------------------

    internal class KisChartException(
        message: String,
        val rtCd: String = "",
        val statusCode: Int = 0,
    ) : RuntimeException(message)

    // -------------------------------------------------------------------------
    // 내부 DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DailyCandleResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output2") val output2: List<CandleRow> = emptyList(),
    ) {
        fun assertSuccess() {
            if (rtCd != "0") {
                throw KisChartException(
                    "[kis-chart] fetchCandles rt_cd=$rtCd msg_cd=$msgCd msg=$msg1",
                    rtCd = rtCd,
                )
            }
        }

        fun toDomain(
            code: String,
            interval: Interval,
            zone: ZoneId,
            fmt: DateTimeFormatter,
        ): List<Candle> =
            output2
                .filter { it.date.isNotBlank() }
                .map { row ->
                    val openTime = LocalDate.parse(row.date, fmt).atStartOfDay(zone).toInstant()
                    Candle(
                        code = code,
                        interval = interval,
                        openTime = openTime,
                        open = row.open.parseDecimal(),
                        high = row.high.parseDecimal(),
                        low = row.low.parseDecimal(),
                        close = row.close.parseDecimal(),
                        volume = row.volume.parseLong(),
                        tradeCount = row.tradeAmount?.parseLong() ?: 0L,
                    )
                }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class CandleRow(
        @JsonProperty("stck_bsop_date") val date: String = "",
        @JsonProperty("stck_oprc") val open: String = "0",
        @JsonProperty("stck_hgpr") val high: String = "0",
        @JsonProperty("stck_lwpr") val low: String = "0",
        @JsonProperty("stck_clpr") val close: String = "0",
        @JsonProperty("acml_vol") val volume: String = "0",
        @JsonProperty("acml_tr_pbmn") val tradeAmount: String? = null,
    )

    companion object {
        /** KIS inquire-daily-itemchartprice 1회 호출 최대 봉 수. */
        const val KIS_PAGE_LIMIT: Int = 100

        /** 페이징 안전 한도. WEEK 200봉 = 2페이지, 여유 2페이지 추가. */
        const val MAX_PAGES: Int = 4

        private fun String.parseDecimal(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(trim())

        private fun String.parseLong(): Long = if (isBlank()) 0L else trim().toLongOrNull() ?: 0L
    }
}

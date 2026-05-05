package com.mj.batch.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Quote
import com.mj.domain.market.kis.port.KisAuthScope
import com.mj.domain.market.kis.port.KisTokenStorePort
import com.mj.domain.market.port.KisRestPort
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
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * real-kis 프로파일용 KisRestPort 구현체.
 *
 * KIS 모의계좌 OpenAPI를 실제로 호출한다.
 * 활성화 조건: `spring.profiles.active=real-kis` + 환경변수 KIS_MOCK_APP_KEY/KIS_MOCK_APP_SECRET 필수.
 *
 * 구현 범위:
 * - token()      : POST /oauth2/tokenP — access_token 발급, JVM 수명 동안 캐시 (TTL = props.tokenTtlSeconds)
 * - fetchCandles(): GET  /uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice
 *                   trId=FHKST03010100, FID_PERIOD_DIV_CODE D/W/M
 * - fetchQuote()  : GET  /uapi/domestic-stock/v1/quotations/inquire-price
 *                   trId=FHKST01010100
 *
 * 에러 처리:
 * - HTTP 401 → cachedToken 무효화 후 1회 재발급 + 재시도
 * - 응답 rt_cd != "0" → KisApiException(message, msgCd, rtCd)
 * - 그 외 HTTP 오류 → KisApiException
 *
 * @see StubKisRestAdapter stub-kis 프로파일 어댑터 (키 없이 동작, CI 기본)
 */
@Component
@Profile("dev | prd")
class RealKisRestAdapter(
    private val props: KisRestProperties,
    private val tokenStore: KisTokenStorePort,
) : KisRestPort {
    private val log = LoggerFactory.getLogger(RealKisRestAdapter::class.java)
    private val zone = ZoneId.of("Asia/Seoul")
    private val yyyymmdd = DateTimeFormatter.ofPattern("yyyyMMdd")

    /** R3-5c-3 PR4: batch는 시스템 키 prod 모드 단일 (백테스트·시세 backfill). */
    private val authScope: KisAuthScope = KisAuthScope.System("prod")

    private val restClient: RestClient =
        RestClient
            .builder()
            .baseUrl(props.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            // HTTP/1.1 강제: JDK HttpClient(기본)는 HTTP/2를 시도해 WireMock 등 HTTP/1.1 서버와 충돌 가능.
            // SimpleClientHttpRequestFactory(java.net.HttpURLConnection 기반)는 HTTP/1.1만 사용한다.
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()

    // -------------------------------------------------------------------------
    // KisRestPort 구현
    // -------------------------------------------------------------------------

    override fun token(): String = tokenStore.currentTokenOrIssue(authScope)

    override fun fetchCandles(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        if (from.isAfter(to)) return emptyList()

        // KIS inquire-daily-itemchartprice는 1회 호출 최대 100봉(역순) — 1년 = 약 250 영업일이라 페이징 필요.
        // 안전 한도 MAX_PAGES — 무한 loop·과도한 호출 방지. 1년 backfill에 3페이지 충분, 6페이지 = 약 2년치.
        val accumulator = mutableListOf<Candle>()
        var cursor = to

        for (page in 0 until MAX_PAGES) {
            if (cursor.isBefore(from)) break

            val pageCandles = fetchCandlesSinglePage(code, interval, from, cursor)
            if (pageCandles.isEmpty()) break

            accumulator.addAll(pageCandles)

            // KIS 응답이 KIS_PAGE_LIMIT(100) 미만이면 from에 도달했거나 더 이전 데이터 없음 → 종료.
            if (pageCandles.size < KIS_PAGE_LIMIT) break

            // 다음 페이지 — 가장 오래된 candle 다음 날을 새 `to`로.
            // (그 candle은 이미 받았으므로 -1day 하지 않으면 한 봉이 두 번 들어옴)
            val oldestDate =
                pageCandles
                    .minBy { it.openTime }
                    .openTime
                    .atZone(zone)
                    .toLocalDate()
            if (!oldestDate.isAfter(from)) break // from 이하 도달
            cursor = oldestDate.minusDays(1)
        }

        // 페이지 경계 중복 가능성 차단 (방어적) + openTime ASC 정렬
        return accumulator.distinctBy { it.openTime }.sortedBy { it.openTime }
    }

    /** 단일 페이지 — KIS API 1회 호출 (최대 KIS_PAGE_LIMIT 봉). */
    private fun fetchCandlesSinglePage(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> =
        executeWithTokenRefresh { token ->
            val periodDivCode = intervalToPeriodDivCode(interval)
            log.debug("[real-kis] fetchCandles page code={} interval={} from={} to={}", code, interval, from, to)

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
                        h["authorization"] = "Bearer $token"
                        h["appkey"] = props.appKey
                        h["appsecret"] = props.appSecret
                        h["tr_id"] = "FHKST03010100"
                    }.retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                            throw KisApiException("401 Unauthorized — token will be refreshed", rtCd = "401")
                        }
                        throw KisApiException(
                            "KIS fetchCandles HTTP error: ${resp.statusCode} for code=$code",
                            rtCd = resp.statusCode.value().toString(),
                        )
                    }.body(DailyCandleResponse::class.java)
                    ?: throw KisApiException("[real-kis] fetchCandles null response for code=$code")

            response.assertSuccess()
            response.toDomain(code, interval, zone, yyyymmdd)
        }

    override fun fetchQuote(code: String): Quote =
        executeWithTokenRefresh { token ->
            log.debug("[real-kis] fetchQuote code={}", code)

            val response =
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-price")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", code)
                            .build()
                    }.headers { h ->
                        h["authorization"] = "Bearer $token"
                        h["appkey"] = props.appKey
                        h["appsecret"] = props.appSecret
                        h["tr_id"] = "FHKST01010100"
                    }.retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                            throw KisApiException("401 Unauthorized — token will be refreshed", rtCd = "401")
                        }
                        throw KisApiException(
                            "KIS fetchQuote HTTP error: ${resp.statusCode} for code=$code",
                            rtCd = resp.statusCode.value().toString(),
                        )
                    }.body(QuoteResponse::class.java)
                    ?: throw KisApiException("[real-kis] fetchQuote null response for code=$code")

            response.assertSuccess()
            response.toDomain(code)
        }

    // -------------------------------------------------------------------------
    // 내부 헬퍼
    // -------------------------------------------------------------------------

    /**
     * 401 응답 시 [KisTokenStorePort.invalidate] + 1회 재발급 후 재시도하는 실행 래퍼.
     * 재시도 후에도 401이 발생하면 즉시 KisApiException 전파.
     *
     * R3-5c-3 PR4: 자체 토큰 캐시 폐기 → port 위임 + scope 단일 (System("prod")).
     */
    private fun <T> executeWithTokenRefresh(action: (String) -> T): T {
        val token = tokenStore.currentTokenOrIssue(authScope)
        return try {
            action(token)
        } catch (ex: KisApiException) {
            if (ex.rtCd == "401") {
                log.warn("[real-kis] 401 received — invalidate + retry once")
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                throw ex
            }
        } catch (ex: RestClientResponseException) {
            if (ex.statusCode == HttpStatus.UNAUTHORIZED) {
                log.warn("[real-kis] 401 HTTP exception — invalidate + retry once")
                tokenStore.invalidate(authScope)
                action(tokenStore.currentTokenOrIssue(authScope))
            } else {
                throw KisApiException(
                    "KIS API HTTP error: ${ex.statusCode} — ${ex.message}",
                    rtCd = ex.statusCode.value().toString(),
                )
            }
        }
    }

    /**
     * Interval → KIS FID_PERIOD_DIV_CODE 매핑 (v3 — ADR 0050 R3-4).
     *
     * inquire-daily-itemchartprice 엔드포인트는 일봉·주봉·월봉만 지원.
     * M1 분봉은 별도 TR_ID(FHKST03010200 — MinuteCandleBackfillJob)로 처리.
     *
     * 지원 매핑:
     *   D1   → "D" (일봉)
     *   WEEK → "W" (주봉, ADR 0050 R3-4 신규)
     *   MONTH→ "M" (월봉, ADR 0050 R3-4 신규)
     *   M1   → fallback "D" + warn (분봉은 별도 Job 사용 요망)
     */
    private fun intervalToPeriodDivCode(interval: Interval): String =
        when (interval) {
            Interval.D1 -> "D"
            Interval.WEEK -> "W"
            Interval.MONTH -> "M"
            Interval.M1 -> {
                log.warn(
                    "[real-kis] interval=M1 is not supported for inquire-daily-itemchartprice — use MinuteCandleBackfillJob instead. Falling back to D.",
                )
                "D"
            }
        }

    // -------------------------------------------------------------------------
    // 내부 DTO — KIS 응답 역직렬화
    // -------------------------------------------------------------------------

    /**
     * 일봉/주봉/월봉 응답.
     * output2: 기간별 OHLCV 배열 (최대 100건)
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class DailyCandleResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output2") val output2: List<CandleRow> = emptyList(),
    ) {
        fun assertSuccess() {
            if (rtCd != "0") {
                throw KisApiException(
                    "[real-kis] fetchCandles error: rt_cd=$rtCd msg_cd=$msgCd msg=$msg1",
                    msgCd = msgCd,
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
        /** 영업일자 yyyyMMdd */
        @JsonProperty("stck_bsop_date") val date: String = "",
        /** 시가 */
        @JsonProperty("stck_oprc") val open: String = "0",
        /** 고가 */
        @JsonProperty("stck_hgpr") val high: String = "0",
        /** 저가 */
        @JsonProperty("stck_lwpr") val low: String = "0",
        /** 종가 */
        @JsonProperty("stck_clpr") val close: String = "0",
        /** 누적 거래량 */
        @JsonProperty("acml_vol") val volume: String = "0",
        /** 누적 거래대금 (선택) */
        @JsonProperty("acml_tr_pbmn") val tradeAmount: String? = null,
    )

    /**
     * 현재가 조회 응답.
     * output: 단일 시세 객체
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class QuoteResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output") val output: QuoteOutput = QuoteOutput(),
    ) {
        fun assertSuccess() {
            if (rtCd != "0") {
                throw KisApiException(
                    "[real-kis] fetchQuote error: rt_cd=$rtCd msg_cd=$msgCd msg=$msg1",
                    msgCd = msgCd,
                    rtCd = rtCd,
                )
            }
        }

        fun toDomain(code: String): Quote =
            Quote(
                code = code,
                at = java.time.Instant.now(),
                price = output.price.parseDecimal(),
                volume = output.volume.parseLong(),
            )
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class QuoteOutput(
        /** 현재가 */
        @JsonProperty("stck_prpr") val price: String = "0",
        /** 누적 거래량 */
        @JsonProperty("acml_vol") val volume: String = "0",
    )

    // -------------------------------------------------------------------------
    // 파싱 유틸 — companion object에 정의하면 nested class에서도 접근 가능
    // -------------------------------------------------------------------------

    companion object {
        /** KIS inquire-daily-itemchartprice 1회 호출 최대 봉 수. */
        const val KIS_PAGE_LIMIT: Int = 100

        /** 페이징 안전 한도 — 1년 = 약 250 영업일이라 3페이지 충분, 6페이지 = 약 2년치. */
        const val MAX_PAGES: Int = 6

        private fun String.parseDecimal(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(trim())

        private fun String.parseLong(): Long = if (isBlank()) 0L else trim().toLongOrNull() ?: 0L
    }
}

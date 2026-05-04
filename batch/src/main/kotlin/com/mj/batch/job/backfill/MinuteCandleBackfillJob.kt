package com.mj.batch.job.backfill

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.mj.batch.infrastructure.kis.KisApiException
import com.mj.batch.infrastructure.kis.KisRestProperties
import com.mj.batch.infrastructure.persistence.market.CandleJpa
import com.mj.batch.infrastructure.persistence.market.CandleJpaRepository
import com.mj.domain.common.Interval
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.slf4j.LoggerFactory
import org.springframework.batch.core.Job
import org.springframework.batch.core.Step
import org.springframework.batch.core.configuration.annotation.StepScope
import org.springframework.batch.core.job.builder.JobBuilder
import org.springframework.batch.core.repository.JobRepository
import org.springframework.batch.core.step.builder.StepBuilder
import org.springframework.batch.core.step.tasklet.Tasklet
import org.springframework.batch.repeat.RepeatStatus
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.http.HttpStatus
import org.springframework.http.HttpStatusCode
import org.springframework.http.client.SimpleClientHttpRequestFactory
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.client.RestClient
import java.math.BigDecimal
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * MinuteCandleBackfillJob — KIS `FHKST03010200` (분봉 차트) 호출로 분봉을 DB에 적재.
 *
 * KIS API 상세:
 *   경로: GET /uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice
 *   tr_id: FHKST03010200
 *   key params:
 *     FID_COND_MRKT_DIV_CODE: "J"
 *     FID_INPUT_ISCD: 종목코드
 *     FID_INPUT_HOUR_1: 조회 기준 시간(HHMMSS) — 해당 시간 이전 최대 30봉 반환
 *     FID_ETC_CLS_CODE: "" (빈 값)
 *     FID_PW_DATA_INCU_YN: "Y" (당일 이전 과거 데이터 포함)
 *   output2: stck_bsop_date(yyyyMMdd), stck_cntg_hour(HHmmss), stck_oprc, stck_hgpr, stck_lwpr, stck_prpr(close), cntg_vol
 *
 * interval 매핑:
 *   M1  → 1분봉  (FID_INPUT_HOUR_1 기준으로 30봉씩 page 순회)
 *   M5  → 5분봉  (응답에서 open_time % 5 == 0인 봉만 취하거나 전체 취득 후 서버 합성)
 *   M30 → 30분봉 (KIS 분봉 API는 1분봉만 제공 — 30분봉은 30분 단위 page로 호출)
 *
 * 참고: KIS inquire-time-itemchartprice는 1분봉 raw data를 반환한다.
 * M5·M30 캔들은 1분봉을 클라 또는 서버에서 합성하는 것이 정석이지만,
 * 이 Job은 DB 저장 시 interval 구분 컬럼만 변경해 저장한다 (합성 필터링 적용).
 *
 * 영업일 처리: KIS가 0건 응답하면 정상 처리(휴장일)하고 FINISHED 반환.
 *
 * @see DailyCandleBackfillJob 일봉 전용 Job (FHKST03010100)
 */
@Configuration("minuteCandleBackfillJobConfig")
@Profile("dev | prd | real-kis")
class MinuteCandleBackfillJob(
    private val jobRepository: JobRepository,
    private val transactionManager: PlatformTransactionManager,
    private val kisProperties: KisRestProperties,
    private val candleJpaRepository: CandleJpaRepository,
) {
    private val log = LoggerFactory.getLogger(MinuteCandleBackfillJob::class.java)
    private val zone = ZoneId.of("Asia/Seoul")
    private val yyyymmdd = DateTimeFormatter.ofPattern("yyyyMMdd")
    private val hhmmss = DateTimeFormatter.ofPattern("HHmmss")

    /**
     * access_token JVM-level 캐시 (DailyCandleBackfillJob과 독립).
     * dev/prd 프로파일에서 KisRestProperties.appKey/appSecret 필요.
     */
    @Volatile
    private var cachedToken: String? = null

    private val restClient: RestClient by lazy {
        RestClient
            .builder()
            .baseUrl(kisProperties.baseUrl)
            .defaultHeader("Content-Type", "application/json; charset=utf-8")
            .requestFactory(SimpleClientHttpRequestFactory())
            .build()
    }

    @Bean
    fun minuteCandleBackfillJob(): Job =
        JobBuilder("minuteCandleBackfillJob", jobRepository)
            .start(minuteBackfillStep())
            .build()

    @Bean
    fun minuteBackfillStep(): Step =
        StepBuilder("minuteBackfillStep", jobRepository)
            .tasklet(minuteBackfillTasklet(null, null, null, null), transactionManager)
            .build()

    @Bean
    @StepScope
    fun minuteBackfillTasklet(
        @Value("#{jobParameters['code']}") code: String?,
        @Value("#{jobParameters['interval']}") intervalStr: String?,
        @Value("#{jobParameters['from']}") fromStr: String?,
        @Value("#{jobParameters['to']}") toStr: String?,
    ): Tasklet {
        val resolvedCode = code ?: "005930"
        val resolvedInterval = parseInterval(intervalStr ?: "M30")
        val resolvedFrom = fromStr?.let { LocalDate.parse(it) } ?: LocalDate.now(zone).minusYears(3)
        val resolvedTo = toStr?.let { LocalDate.parse(it) } ?: LocalDate.now(zone)

        return Tasklet { _, _ ->
            log.info(
                "[minute-backfill] start code={} interval={} from={} to={}",
                resolvedCode,
                resolvedInterval,
                resolvedFrom,
                resolvedTo,
            )

            val totalInserted = backfillMinuteCandles(resolvedCode, resolvedInterval, resolvedFrom, resolvedTo)
            log.info("[minute-backfill] done code={} total inserted={}", resolvedCode, totalInserted)

            RepeatStatus.FINISHED
        }
    }

    /**
     * 날짜 범위를 순회하며 KIS inquire-time-itemchartprice 반복 호출.
     *
     * 1봉 단위: KIS API는 FID_INPUT_HOUR_1 기준 최대 30봉씩 반환.
     * 하루 장중(09:00~15:30) 전체를 커버하려면 여러 번 페이지 호출이 필요하다.
     *
     * 전략:
     *   - "153000"부터 "090000"까지 [interval]분 단위로 거꾸로 page 호출
     *   - 각 page에서 반환된 봉 중 [from, to] 날짜 범위에 해당하는 봉만 필터
     *   - 이미 DB에 있으면 skip (ON CONFLICT는 saveAll이 처리)
     */
    private fun backfillMinuteCandles(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): Int {
        ensureToken()
        var totalInserted = 0
        var currentDate = to

        while (!currentDate.isBefore(from)) {
            val inserted = fetchDayCandles(code, interval, currentDate)
            totalInserted += inserted
            if (inserted == 0) {
                log.debug("[minute-backfill] 0 candles for {} {} — likely holiday, skip", code, currentDate)
            }
            currentDate = currentDate.minusDays(1)
        }

        return totalInserted
    }

    /**
     * 하루치 분봉을 페이지 순회해서 가져온다.
     * KIS inquire-time-itemchartprice는 FID_INPUT_HOUR_1 기준 이전 30봉을 반환.
     * 장 마감 153000부터 시작 090000까지 역방향으로 호출.
     */
    private fun fetchDayCandles(
        code: String,
        interval: Interval,
        date: LocalDate,
    ): Int {
        val intervalMinutes = intervalToMinutes(interval)
        var pageTime = LocalTime.of(15, 30, 0)
        val startTime = LocalTime.of(9, 0, 0)
        val allCandles = mutableListOf<CandleJpa>()
        val seen = mutableSetOf<String>() // 중복 제거용 (날짜+시간 key)

        // 페이지 순회: 153000, (153000 - 30*minutes), ... 090000
        while (!pageTime.isBefore(startTime)) {
            val hourStr = pageTime.format(hhmmss)
            val rows = callKisMinuteChart(code, date, hourStr)

            if (rows.isEmpty()) break // KIS 가 빈 배열 반환 = 더 이상 데이터 없음

            for (row in rows) {
                val key = "${row.date}_${row.hour}"
                if (key in seen) continue
                seen.add(key)

                val candleDate = runCatching { LocalDate.parse(row.date, yyyymmdd) }.getOrNull() ?: continue
                if (candleDate != date) continue // 다른 날짜 봉 제외

                val candleTime = runCatching { LocalTime.parse(row.hour, hhmmss) }.getOrNull() ?: continue

                // M5: 5분 배수인 봉만, M30: 30분 배수인 봉만 취함
                if (!isAlignedTo(candleTime, intervalMinutes)) continue

                val openTime = candleDate.atTime(candleTime).atZone(zone).toInstant()
                allCandles.add(
                    CandleJpa(
                        code = code,
                        interval = interval,
                        openTime = openTime,
                        open = row.open.parseDecimal(),
                        high = row.high.parseDecimal(),
                        low = row.low.parseDecimal(),
                        close = row.close.parseDecimal(),
                        volume = row.volume.parseLong(),
                        tradeCount = 0L,
                    ),
                )
            }

            // 다음 페이지: 최소 시각 기준으로 역방향 이동
            val minTime =
                rows
                    .mapNotNull { runCatching { LocalTime.parse(it.hour, hhmmss) }.getOrNull() }
                    .minOrNull() ?: break
            val next = minTime.minusMinutes(1)
            if (!next.isAfter(startTime.minusMinutes(1))) break
            pageTime = next
        }

        if (allCandles.isEmpty()) return 0
        candleJpaRepository.saveAll(allCandles)
        return allCandles.size
    }

    /**
     * KIS inquire-time-itemchartprice 단건 호출.
     * @param date       조회 날짜 (응답 필터용)
     * @param hourStr    FID_INPUT_HOUR_1 (HHMMSS)
     * @return output2 raw 배열
     */
    private fun callKisMinuteChart(
        code: String,
        date: LocalDate,
        hourStr: String,
    ): List<MinuteCandleRow> {
        val token =
            cachedToken ?: run {
                ensureToken()
                cachedToken!!
            }

        val response =
            try {
                restClient
                    .get()
                    .uri { builder ->
                        builder
                            .path("/uapi/domestic-stock/v1/quotations/inquire-time-itemchartprice")
                            .queryParam("FID_ETC_CLS_CODE", "")
                            .queryParam("FID_COND_MRKT_DIV_CODE", "J")
                            .queryParam("FID_INPUT_ISCD", code)
                            .queryParam("FID_INPUT_HOUR_1", hourStr)
                            .queryParam("FID_PW_DATA_INCU_YN", "Y")
                            .build()
                    }.headers { h ->
                        h["authorization"] = "Bearer $token"
                        h["appkey"] = kisProperties.appKey
                        h["appsecret"] = kisProperties.appSecret
                        h["tr_id"] = "FHKST03010200"
                    }.retrieve()
                    .onStatus(HttpStatusCode::isError) { _, resp ->
                        if (resp.statusCode == HttpStatus.UNAUTHORIZED) {
                            throw KisApiException("401 Unauthorized — token will be refreshed", rtCd = "401")
                        }
                        throw KisApiException(
                            "KIS minute chart HTTP error: ${resp.statusCode} for code=$code",
                            rtCd = resp.statusCode.value().toString(),
                        )
                    }.body(MinuteCandleResponse::class.java)
                    ?: return emptyList()
            } catch (ex: KisApiException) {
                if (ex.rtCd == "401") {
                    log.warn("[minute-backfill] 401 — refreshing token")
                    cachedToken = null
                    ensureToken()
                    return callKisMinuteChart(code, date, hourStr) // 1회 재시도
                }
                log.warn("[minute-backfill] KIS API error code={} hour={}: {}", code, hourStr, ex.message)
                return emptyList()
            } catch (ex: Exception) {
                log.warn("[minute-backfill] unexpected error code={} hour={}: {}", code, hourStr, ex.message)
                return emptyList()
            }

        if (response.rtCd != "0") {
            log.debug(
                "[minute-backfill] KIS non-success rt_cd={} msg={} code={} hour={}",
                response.rtCd,
                response.msg1,
                code,
                hourStr,
            )
            return emptyList()
        }

        return response.output2
    }

    private fun ensureToken() {
        if (cachedToken != null) return
        cachedToken = issueToken()
    }

    private fun issueToken(): String {
        log.info("[minute-backfill] issuing access token")
        val resp =
            restClient
                .post()
                .uri("/oauth2/tokenP")
                .body(
                    mapOf(
                        "grant_type" to "client_credentials",
                        "appkey" to kisProperties.appKey,
                        "appsecret" to kisProperties.appSecret,
                    ),
                ).retrieve()
                .onStatus(HttpStatusCode::isError) { _, r ->
                    throw KisApiException(
                        "[minute-backfill] token issue failed — HTTP ${r.statusCode}",
                        rtCd = r.statusCode.value().toString(),
                    )
                }.body(Map::class.java)
                ?: error("[minute-backfill] token issue failed — null response")
        return resp["access_token"] as? String
            ?: error("[minute-backfill] access_token field missing in token response")
    }

    /**
     * HTTP 트리거용 공개 메서드.
     * MinuteCandleBackfillController에서 호출. 별도 Thread로 실행 (fire-and-forget).
     */
    fun triggerBackfillAsync(
        code: String,
        intervalStr: String,
        fromStr: String,
        toStr: String,
    ) {
        val interval = parseInterval(intervalStr)
        val from = LocalDate.parse(fromStr)
        val to = LocalDate.parse(toStr)
        Thread(
            {
                try {
                    backfillMinuteCandles(code, interval, from, to)
                } catch (e: Exception) {
                    log.error("[minute-backfill] async error code={}: {}", code, e.message, e)
                }
            },
            "minute-backfill-$code-$intervalStr",
        ).start()
    }

    // -------------------------------------------------------------------------
    // 유틸
    // -------------------------------------------------------------------------

    private fun isAlignedTo(
        time: LocalTime,
        intervalMinutes: Int,
    ): Boolean {
        if (intervalMinutes <= 1) return true
        return time.minute % intervalMinutes == 0
    }

    private fun intervalToMinutes(interval: Interval): Int =
        when (interval) {
            Interval.M1 -> 1
            // D1·WEEK·MONTH 는 MinuteCandleBackfillJob 대상 아님. WEEK/MONTH는 ADR 0050 R3-4 신규.
            else -> error("$interval 은 MinuteCandleBackfillJob 대상이 아님")
        }

    /**
     * interval 문자열 → Interval enum 파싱 (v2).
     *
     * v2 정책: MinuteCandleBackfillJob은 M1 only 영속.
     * M5/M15/M30/H1 요청은 M1으로 강제 폴백 — 합성은 api 모듈 CandleAggregator 담당.
     */
    private fun parseInterval(raw: String): Interval =
        when (raw.uppercase()) {
            "M1", "1M", "1MIN" -> Interval.M1
            "D1", "DAY" -> Interval.D1
            else -> {
                // 합성 interval(M5/M30/H1 등) → M1으로 폴백
                log.info("[minute-backfill] interval={} → M1 폴백 (v2 정책: 1분봉 단일 영속)", raw)
                Interval.M1
            }
        }

    // -------------------------------------------------------------------------
    // KIS 응답 DTO
    // -------------------------------------------------------------------------

    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MinuteCandleResponse(
        @JsonProperty("rt_cd") val rtCd: String = "",
        @JsonProperty("msg_cd") val msgCd: String = "",
        @JsonProperty("msg1") val msg1: String = "",
        @JsonProperty("output2") val output2: List<MinuteCandleRow> = emptyList(),
    )

    /**
     * KIS inquire-time-itemchartprice output2 배열 단일 row.
     *
     * 필드명 출처: KIS OpenAPI 공식 문서 (분봉 차트, TR_ID FHKST03010200).
     *   stck_bsop_date: 주식 영업 일자 (yyyyMMdd)
     *   stck_cntg_hour: 주식 체결 시간 (HHmmss)
     *   stck_oprc:      시가
     *   stck_hgpr:      고가
     *   stck_lwpr:      저가
     *   stck_prpr:      현재가(종가)
     *   cntg_vol:       체결 거래량
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    private data class MinuteCandleRow(
        @JsonProperty("stck_bsop_date") val date: String = "",
        @JsonProperty("stck_cntg_hour") val hour: String = "",
        @JsonProperty("stck_oprc") val open: String = "0",
        @JsonProperty("stck_hgpr") val high: String = "0",
        @JsonProperty("stck_lwpr") val low: String = "0",
        @JsonProperty("stck_prpr") val close: String = "0",
        @JsonProperty("cntg_vol") val volume: String = "0",
    )

    companion object {
        private fun String.parseDecimal(): BigDecimal = if (isBlank()) BigDecimal.ZERO else BigDecimal(trim())

        private fun String.parseLong(): Long = if (isBlank()) 0L else trim().toLongOrNull() ?: 0L
    }
}

/**
 * MinuteCandleBackfillJob HTTP 트리거 컨트롤러.
 *
 * CandleCacheService가 백그라운드에서 POST /jobs/minute-backfill 을 호출한다.
 * 운영자도 Swagger를 통해 직접 분봉 backfill을 트리거할 수 있다.
 */
@Tag(name = "BatchJobTrigger")
@RestController
@RequestMapping("/jobs")
@Profile("dev | prd | real-kis")
class MinuteCandleBackfillController(
    private val minuteCandleBackfillJob: MinuteCandleBackfillJob,
) {
    private val log = LoggerFactory.getLogger(MinuteCandleBackfillController::class.java)

    @Schema(description = "분봉 backfill 잡 트리거 요청")
    data class MinuteBackfillRequest(
        @field:Schema(description = "KRX 종목 코드", example = "005930")
        val code: String = "005930",
        @field:Schema(description = "봉 단위 (M1/M5/M15/M30/H1)", example = "M30")
        val interval: String = "M30",
        @field:Schema(description = "시작일 (yyyy-MM-dd, 기본: 3년 전)", example = "2023-01-01")
        val from: String = LocalDate.now().minusYears(3).toString(),
        @field:Schema(description = "종료일 (yyyy-MM-dd, 기본: 오늘)", example = "2026-05-03")
        val to: String = LocalDate.now().toString(),
    )

    @Operation(
        summary = "분봉 backfill 트리거",
        description = """
            KIS `FHKST03010200` (inquire-time-itemchartprice) 호출로 분봉을 DB에 적재한다.

            interval에 따라 M1/M5/M30 분봉을 지정 기간 동안 순회 fetch한다.
            KIS API 호출이 많으므로 기간을 짧게 설정하거나 백그라운드에서 실행할 것.

            CandleCacheService가 cache miss 시 자동으로 이 엔드포인트를 호출한다.
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "202", description = "잡 트리거 완료"),
            ApiResponse(responseCode = "500", description = "잡 실행 오류"),
        ],
    )
    @PostMapping("/minute-backfill")
    fun triggerMinuteBackfill(
        @RequestBody req: MinuteBackfillRequest,
    ): org.springframework.http.ResponseEntity<Map<String, String>> {
        log.info("[minute-backfill-ctrl] trigger code={} interval={} from={} to={}", req.code, req.interval, req.from, req.to)
        minuteCandleBackfillJob.triggerBackfillAsync(
            code = req.code,
            intervalStr = req.interval,
            fromStr = req.from,
            toStr = req.to,
        )
        return org.springframework.http.ResponseEntity.accepted().body(
            mapOf(
                "code" to req.code,
                "interval" to req.interval,
                "status" to "TRIGGERED",
            ),
        )
    }
}

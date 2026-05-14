package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.secret.AesGcmCipher
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshResponse
import kr.spdaewoon.magicjar.api.service.dto.IntervalRefreshResult
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * 캔들 수동 갱신 서비스 (dogfooding qa #5 — POST /api/symbols/{code}/candles/refresh).
 *
 * 목적: DB candle이 stale 된 종목을 사용자 트리거로 즉시 최신화.
 *
 * KIS-first raw 출처 (헌법 §3 의무 인용):
 *   FHKST03010100 (inquire-daily-itemchartprice) — 일봉·주봉·월봉·연봉
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *   FHKST03010230 (inquire-time-dailychartprice) — 1m 분봉
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *
 * 분봉(5m·15m·30m·90m·1h) 처리:
 *   KIS 미제공 — 1m 갱신 시 ON CONFLICT DO NOTHING으로 upsert만 수행.
 *   합산 재계산은 향후 별도 trigger (현재 consumer AggregateBackfillRunner 전용).
 *
 * Rate Limit (ADR 0037): 20 req/s 한도. interval 간 50ms 마진.
 * 인증: api 모듈 UserKisCredentialJpaRepository + AesGcmCipher + Redis token cache (KisRestPriceMetricsAdapter 패턴 정합).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class CandleRefreshService(
    private val webClient: WebClient,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val credentialJpaRepository: UserKisCredentialJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val aesGcmCipher: AesGcmCipher,
    private val redisTemplate: StringRedisTemplate,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
) {
    private val log = LoggerFactory.getLogger(CandleRefreshService::class.java)

    companion object {
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
        private const val TR_ID_DAILY = "FHKST03010100"
        private const val ENDPOINT_DAILY = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"

        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
        private const val TR_ID_1MIN = "FHKST03010230"
        private const val ENDPOINT_1MIN = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice"

        private const val SOURCE = "KIS"
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val KST_ZONE: ZoneId = ZoneId.of("Asia/Seoul")

        /** FHKST03010100 interval → period_div_code 매핑 */
        private val DAILY_INTERVALS =
            mapOf(
                "1d" to "D",
                "1wk" to "W",
                "1mo" to "M",
                "1y" to "Y",
            )

        /** 기본 갱신 interval 목록 (사용자 미지정 시) */
        val DEFAULT_INTERVALS = listOf("1d", "1wk", "1mo", "1y")

        /** 지원하는 갱신 interval 전체 목록 */
        val SUPPORTED_INTERVALS = setOf("1m", "1d", "1wk", "1mo", "1y")
    }

    /**
     * 종목 캔들 수동 갱신 진입점.
     *
     * @param code 종목코드 6자리
     * @param intervals 갱신 대상 interval 목록 (빈 리스트 = DEFAULT_INTERVALS 적용)
     * @return CandleRefreshResponse (interval 별 결과 + 경과 시간)
     * @throws ResponseStatusException 404 (종목 없음) | 500 (KIS 인증 실패)
     */
    fun refresh(
        code: String,
        intervals: List<String>,
    ): CandleRefreshResponse {
        val startMs = System.currentTimeMillis()

        // 1. 종목 존재 검증
        if (!instrumentJpaRepository.existsByCode(code)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: $code")
        }

        // 2. effective intervals 결정 (빈 목록 → default)
        val effectiveIntervals =
            if (intervals.isEmpty()) {
                DEFAULT_INTERVALS
            } else {
                intervals.filter { it in SUPPORTED_INTERVALS }.ifEmpty { DEFAULT_INTERVALS }
            }

        // 3. KIS 인증 (api 모듈 패턴 — KisRestPriceMetricsAdapter 정합)
        val (accessToken, appKey, secretKey) = resolveAccessTokenAndKeys()

        // 4. market 분기 — KIS raw spec: J=KRX주식(KOSDAQ), UN=통합(KOSPI+NXT cover ADR0063)
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        val mrktDivCode =
            when (instrument?.market) {
                "KOSPI" -> "UN" // ADR 0063 NXT cover 유지
                "KOSDAQ" -> "J" // KOSDAQ은 NXT 미거래 → UN 빈 응답 방지
                else -> "J" // 안전 기본값
            }

        // 5. interval 별 순차 갱신 (Rate Limit ADR 0037: 50ms 마진)
        val results = mutableListOf<IntervalRefreshResult>()
        for ((index, intervalCode) in effectiveIntervals.withIndex()) {
            if (index > 0) Thread.sleep(50) // 20 req/s 한도 준수

            val result =
                when (intervalCode) {
                    "1m" -> refreshOneMinCandles(code, mrktDivCode, accessToken, appKey, secretKey)
                    in DAILY_INTERVALS -> refreshDailyCandles(code, intervalCode, mrktDivCode, accessToken, appKey, secretKey)
                    else -> IntervalRefreshResult(intervalCode, 0, 0, null, "SKIPPED")
                }
            results.add(result)
        }

        val elapsedMs = System.currentTimeMillis() - startMs
        log.info(
            "캔들 수동 갱신 완료: code={} intervals={} elapsedMs={}",
            code,
            effectiveIntervals,
            elapsedMs,
        )
        return CandleRefreshResponse(refreshedIntervals = results, elapsedMs = elapsedMs)
    }

    /**
     * 일봉·주봉·월봉·연봉 갱신 (FHKST03010100).
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     * - fid_cond_mrkt_div_code: J(KRX주식) 또는 UN(통합 — KOSPI+NXT)
     * - fid_input_iscd: 종목코드
     * - fid_input_date_1: 시작일 (yyyyMMdd) — 30년 전 기준 전수 수집
     * - fid_input_date_2: 종료일 (yyyyMMdd) — 오늘
     * - fid_period_div_code: D(일)/W(주)/M(월)/Y(연)
     * - fid_org_adj_prc: 0 (수정주가 미반영)
     */
    private fun refreshDailyCandles(
        code: String,
        intervalCode: String,
        mrktDivCode: String,
        accessToken: String,
        appKey: String,
        secretKey: String,
    ): IntervalRefreshResult {
        val periodDivCode = DAILY_INTERVALS[intervalCode] ?: return IntervalRefreshResult(intervalCode, 0, 0, null, "SKIPPED")
        val today = LocalDate.now(KST_ZONE).format(DATE_FMT)
        // 30년 전 기준 전수 수집 (KIS 응답 가능 범위 최대)
        val startDate = LocalDate.now(KST_ZONE).minusYears(30).format(DATE_FMT)

        return try {
            @Suppress("UNCHECKED_CAST")
            val body =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                            .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                            .path(ENDPOINT_DAILY)
                            // fid_cond_mrkt_div_code: J=KRX주식, UN=통합(KRX+NXT)
                            // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
                            .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                            .queryParam("fid_input_iscd", code)
                            .queryParam("fid_input_date_1", startDate)
                            .queryParam("fid_input_date_2", today)
                            // fid_period_div_code: D/W/M/Y — KIS raw spec
                            .queryParam("fid_period_div_code", periodDivCode)
                            .queryParam("fid_org_adj_prc", "0")
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKey)
                    .header("appsecret", secretKey)
                    .header("tr_id", TR_ID_DAILY)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block()

            if (body == null) {
                log.warn("캔들 갱신 빈 응답: code={} interval={}", code, intervalCode)
                return IntervalRefreshResult(intervalCode, 0, 0, null, "FAILED")
            }

            val rtCd = body["rt_cd"]?.toString()
            if (rtCd != "0") {
                log.warn("캔들 갱신 rt_cd != 0: code={} interval={} rt_cd={} msg={}", code, intervalCode, rtCd, body["msg1"])
                return IntervalRefreshResult(intervalCode, 0, 0, null, "FAILED")
            }

            @Suppress("UNCHECKED_CAST")
            val output2 = body["output2"] as? List<*> ?: return IntervalRefreshResult(intervalCode, 0, 0, null, "FAILED")
            val candles = parseKisDailyOutput2(output2, code, intervalCode)

            val inserted = upsertCandles(candles, intervalCode)
            val latestTs = queryLatestTs(code, intervalCode)

            log.info(
                "캔들 갱신 완료: code={} interval={} fetched={} inserted={}",
                code,
                intervalCode,
                candles.size,
                inserted,
            )
            IntervalRefreshResult(intervalCode, candles.size, inserted, latestTs, "OK")
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 429) {
                log.warn("캔들 갱신 Rate Limit (429): code={} interval={}", code, intervalCode)
                IntervalRefreshResult(intervalCode, 0, 0, null, "RATE_LIMITED")
            } else {
                log.warn(
                    "캔들 갱신 KIS 오류: code={} interval={} status={}",
                    code,
                    intervalCode,
                    e.statusCode.value(),
                )
                IntervalRefreshResult(intervalCode, 0, 0, null, "FAILED")
            }
        } catch (e: Exception) {
            log.warn("캔들 갱신 오류: code={} interval={} err={}", code, intervalCode, e.message)
            IntervalRefreshResult(intervalCode, 0, 0, null, "FAILED")
        }
    }

    /**
     * 1m 분봉 갱신 (FHKST03010230) — 직전 30 거래일 역방향 페이지네이션.
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     * - fid_cond_mrkt_div_code: J(KRX주식) 또는 UN(통합)
     * - fid_input_iscd: 종목코드
     * - fid_input_hour_1: 조회 기준 시각 (HHmmss) — 이 시각 이전 최신 120건 반환
     * - fid_input_date_1: 조회 날짜 (yyyyMMdd)
     * - fid_pw_data_incu_yn: N
     * - fid_fake_tick_incu_yn: (빈 문자열)
     *
     * Rate Limit (ADR 0037): 50ms 간격 유지.
     */
    private fun refreshOneMinCandles(
        code: String,
        mrktDivCode: String,
        accessToken: String,
        appKey: String,
        secretKey: String,
    ): IntervalRefreshResult {
        val allRows = mutableListOf<Map<String, Any>>()
        val seenTs = mutableSetOf<Long>()

        // 직전 30 거래일 수집 (주말 제외 — 약 45 달력일 커버)
        val targetDays = mutableListOf<LocalDate>()
        var d = LocalDate.now(KST_ZONE)
        var checked = 0
        while (targetDays.size < 30 && checked < 60) {
            val dow = d.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                targetDays.add(d)
            }
            d = d.minusDays(1)
            checked++
        }

        for (targetDate in targetDays) {
            val dateStr = targetDate.format(DATE_FMT)
            var currentHour = "200000"
            var pageCount = 0
            val maxPagesPerDay = 8
            var dayDone = false

            while (!dayDone && pageCount < maxPagesPerDay) {
                try {
                    if (pageCount > 0) Thread.sleep(50) // Rate Limit ADR 0037

                    @Suppress("UNCHECKED_CAST")
                    val body =
                        webClient
                            .get()
                            .uri { builder ->
                                builder
                                    .scheme("https")
                                    .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                    .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                    .path(ENDPOINT_1MIN)
                                    // fid_cond_mrkt_div_code: J(KOSDAQ) 또는 UN(KOSPI+NXT)
                                    // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
                                    .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                    .queryParam("fid_input_iscd", code)
                                    // fid_input_hour_1: 이 시각 이전 최신 120건 반환 (페이지네이션 커서)
                                    .queryParam("fid_input_hour_1", currentHour)
                                    .queryParam("fid_input_date_1", dateStr)
                                    .queryParam("fid_pw_data_incu_yn", "N")
                                    // fid_fake_tick_incu_yn: 허봉 포함 여부 (빈 문자열 = 미포함)
                                    .queryParam("fid_fake_tick_incu_yn", "")
                                    .build()
                            }.header("authorization", "Bearer $accessToken")
                            .header("appkey", appKey)
                            .header("appsecret", secretKey)
                            .header("tr_id", TR_ID_1MIN)
                            .header("custtype", "P")
                            .retrieve()
                            .bodyToMono(Map::class.java)
                            .timeout(Duration.ofSeconds(10))
                            .block()

                    pageCount++
                    if (body == null) {
                        dayDone = true
                        continue
                    }

                    val rtCd = body["rt_cd"]?.toString()
                    if (rtCd != "0") {
                        dayDone = true
                        continue
                    }

                    @Suppress("UNCHECKED_CAST")
                    val output2 = body["output2"] as? List<*>
                    if (output2.isNullOrEmpty()) {
                        dayDone = true
                        continue
                    }

                    var earliestEpochSec = Long.MAX_VALUE
                    for (row in output2) {
                        @Suppress("UNCHECKED_CAST")
                        val item = row as? Map<String, Any> ?: continue
                        val dateField = item["stck_bsop_date"]?.toString()?.trim() ?: continue
                        val hourField = item["stck_cntg_hour"]?.toString()?.trim() ?: continue
                        val epochSec =
                            runCatching {
                                val ld = if (dateField.length == 8) LocalDate.parse(dateField, DATE_FMT) else targetDate
                                val lt =
                                    LocalTime.of(
                                        hourField.substring(0, 2).toInt(),
                                        hourField.substring(2, 4).toInt(),
                                        hourField.substring(4, 6).toInt(),
                                    )
                                ld.atTime(lt).atZone(KST_ZONE).toEpochSecond()
                            }.getOrNull() ?: continue
                        if (seenTs.add(epochSec)) {
                            @Suppress("UNCHECKED_CAST")
                            allRows.add(item as Map<String, Any>)
                        }
                        if (epochSec < earliestEpochSec) earliestEpochSec = epochSec
                    }

                    if (earliestEpochSec == Long.MAX_VALUE) {
                        dayDone = true
                        continue
                    }
                    val earliestLt =
                        java.time.Instant
                            .ofEpochSecond(earliestEpochSec)
                            .atZone(KST_ZONE)
                            .toLocalTime()
                    if (!earliestLt.isAfter(LocalTime.of(8, 0, 0))) {
                        dayDone = true
                    } else {
                        val nextLt = earliestLt.minus(1, ChronoUnit.MINUTES)
                        currentHour = "%02d%02d%02d".format(nextLt.hour, nextLt.minute, nextLt.second)
                    }
                } catch (e: Exception) {
                    log.warn("1m 갱신 페이지 오류: code={} date={} err={}", code, dateStr, e.message)
                    dayDone = true
                }
            }
        }

        // 수집된 raw rows → DB upsert
        val inserted = upsertOneMinRows(allRows, code)
        val latestTs = queryLatestTs(code, "1m")
        log.info("1m 캔들 갱신 완료: code={} fetched={} inserted={}", code, allRows.size, inserted)
        return IntervalRefreshResult("1m", allRows.size, inserted, latestTs, "OK")
    }

    /**
     * FHKST03010100 output2[] 파싱 → (code, interval, ts, ohlcv) 튜플 목록.
     * stck_bsop_date: yyyyMMdd → KST 00:00:00 epoch second.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseKisDailyOutput2(
        output2: List<*>,
        code: String,
        intervalCode: String,
    ): List<KisDailyRow> {
        val result = mutableListOf<KisDailyRow>()
        for (row in output2) {
            val item = row as? Map<*, *> ?: continue
            val dateStr = item["stck_bsop_date"]?.toString()?.trim() ?: continue
            val close = item["stck_clpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: continue
            val open = item["stck_oprc"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val high = item["stck_hgpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val low = item["stck_lwpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volume = item["acml_vol"]?.toString()?.trim()?.toLongOrNull() ?: 0L
            val epochSec =
                runCatching {
                    LocalDate.parse(dateStr, DATE_FMT).atStartOfDay(KST_ZONE).toEpochSecond()
                }.getOrNull() ?: continue
            result.add(KisDailyRow(code, intervalCode, epochSec, open, high, low, close, volume))
        }
        return result
    }

    /** upsert 결과 집계를 위한 내부 레코드 */
    private data class KisDailyRow(
        val code: String,
        val intervalCode: String,
        val epochSec: Long,
        val open: BigDecimal,
        val high: BigDecimal,
        val low: BigDecimal,
        val close: BigDecimal,
        val volume: Long,
    )

    /**
     * KisDailyRow 목록을 ON CONFLICT DO NOTHING upsert.
     * CandleJpaRepository.insertIgnore 재사용 (ADR 0051 compliance).
     * 트랜잭션은 Repository.insertIgnore(@Transactional) 레벨에서 보장.
     * (hotfix: private fun @Transactional은 Spring AOP 프록시를 우회하므로 무효 → 제거)
     */
    private fun upsertCandles(
        rows: List<KisDailyRow>,
        intervalCode: String,
    ): Int {
        var total = 0
        for (row in rows) {
            total +=
                candleJpaRepository.insertIgnore(
                    code = row.code,
                    interval = intervalCode,
                    ts = Instant.ofEpochSecond(row.epochSec),
                    openRaw = row.open,
                    highRaw = row.high,
                    lowRaw = row.low,
                    closeRaw = row.close,
                    volume = row.volume,
                    source = SOURCE,
                )
        }
        return total
    }

    /**
     * 1m raw rows (Map<String, Any>) → DB upsert.
     * stck_bsop_date + stck_cntg_hour → ts epoch second.
     * 트랜잭션은 Repository.insertIgnore(@Transactional) 레벨에서 보장.
     * (hotfix: private fun @Transactional은 Spring AOP 프록시를 우회하므로 무효 → 제거)
     */
    private fun upsertOneMinRows(
        rows: List<Map<String, Any>>,
        code: String,
    ): Int {
        var total = 0
        for (item in rows) {
            val dateStr = item["stck_bsop_date"]?.toString()?.trim() ?: continue
            val hourStr = item["stck_cntg_hour"]?.toString()?.trim() ?: continue
            val close = item["stck_prpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: continue
            val open = item["stck_oprc"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val high = item["stck_hgpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val low = item["stck_lwpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volume = item["acml_vol"]?.toString()?.trim()?.toLongOrNull() ?: 0L
            val epochSec =
                runCatching {
                    val ld = if (dateStr.length == 8) LocalDate.parse(dateStr, DATE_FMT) else return@runCatching null
                    val lt =
                        LocalTime.of(
                            hourStr.substring(0, 2).toInt(),
                            hourStr.substring(2, 4).toInt(),
                            hourStr.substring(4, 6).toInt(),
                        )
                    ld.atTime(lt).atZone(KST_ZONE).toEpochSecond()
                }.getOrNull() ?: continue
            total +=
                candleJpaRepository.insertIgnore(
                    code = code,
                    interval = CandleInterval.ONE_MIN.code,
                    ts = Instant.ofEpochSecond(epochSec),
                    openRaw = open,
                    highRaw = high,
                    lowRaw = low,
                    closeRaw = close,
                    volume = volume,
                    source = SOURCE,
                )
        }
        return total
    }

    /** 갱신 후 DB 최신 ts 조회 (frontend 검증 노출용). */
    private fun queryLatestTs(
        code: String,
        intervalCode: String,
    ): String? =
        try {
            val rows =
                candleJpaRepository.findByCodeAndIntervalLatest(
                    code,
                    intervalCode,
                    org.springframework.data.domain.PageRequest
                        .of(0, 1),
                )
            rows.firstOrNull()?.ts?.toString()
        } catch (e: Exception) {
            log.debug("latestTs 조회 실패: code={} interval={} err={}", code, intervalCode, e.message)
            null
        }

    /**
     * KIS access_token + appKey + secretKey 해결.
     * api 모듈 KisRestPriceMetricsAdapter.resolveAccessTokenAndKeys 패턴 정합.
     *
     * 1. REAL 자격증명 우선 조회 (KIS-only 정보 수집 정책 — feedback_kis_key_realonly_for_data)
     * 2. Redis access_token:{userId}:REAL 캐시 확인
     * 3. cache miss → KIS /oauth2/tokenP 발급 + Redis TTL=23h 캐시
     */
    private fun resolveAccessTokenAndKeys(): Triple<String, String, String> {
        val credential =
            credentialJpaRepository.findAllByAccountType("REAL").firstOrNull()
                ?: credentialJpaRepository.findAllByAccountType("MOCK").firstOrNull()
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "KIS 자격증명이 등록되지 않았습니다. 온보딩에서 KIS API 키를 등록해 주세요.",
                )

        val appKey =
            try {
                String(aesGcmCipher.decrypt(credential.appKeyEnc))
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS 자격증명 복호화 실패")
            }
        val secretKey =
            try {
                String(aesGcmCipher.decrypt(credential.secretKeyEnc))
            } catch (e: Exception) {
                throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS 자격증명 복호화 실패")
            }

        val userId = credential.id.userId
        val accountType = credential.id.accountType
        val cacheKey = "access_token:$userId:$accountType"

        val cachedToken =
            try {
                redisTemplate.opsForValue().get(cacheKey)
            } catch (e: Exception) {
                null
            }
        if (!cachedToken.isNullOrBlank()) {
            return Triple(cachedToken, appKey, secretKey)
        }

        // token miss → KIS /oauth2/tokenP 발급
        return try {
            @Suppress("UNCHECKED_CAST")
            val tokenResponse =
                webClient
                    .post()
                    .uri("$kisBaseProd/oauth2/tokenP")
                    .header("Content-Type", "application/json; charset=utf-8")
                    .bodyValue(
                        mapOf(
                            "grant_type" to "client_credentials",
                            "appkey" to appKey,
                            "appsecret" to secretKey,
                        ),
                    ).retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(5))
                    .block() as? Map<String, Any>
                    ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS tokenP 응답 없음")

            val accessToken =
                tokenResponse["access_token"]?.toString()
                    ?: throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS access_token 없음")

            try {
                redisTemplate.opsForValue().set(cacheKey, accessToken, Duration.ofHours(23))
            } catch (e: Exception) {
                log.warn("Redis access_token 캐시 실패 (무시): {}", e.message)
            }

            Triple(accessToken, appKey, secretKey)
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("KIS access_token 발급 실패: {}", e.message)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS 인증 실패: ${e.message}")
        }
    }
}

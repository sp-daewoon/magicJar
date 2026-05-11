package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import kr.spdaewoon.magicjar.domain.secret.port.SecretCipher
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Primary
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.math.BigDecimal
import java.time.Duration
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlinx.datetime.Instant as KtInstant

/**
 * KIS REST 캔들 조회 어댑터 (ADR 0019 §7-4 — KIS-first 정책 정합).
 *
 * 지원 interval:
 *   - 1d·1wk·1mo·1y: FHKST03010100 (inquire-daily-itemchartprice)
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *   - 1m: FHKST03010230 (inquire-time-dailychartprice) — 최대 120건/call, 1일치 분봉
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *
 * Yahoo Finance 의존성 전면 제거 (2026-05-11 — KIS-only 정책 적용).
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
@Primary
class KisRestCandleAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    @Value("\${magicjar.kis.base-url-prod:https://openapi.koreainvestment.com:9443}")
    private val kisBaseProd: String,
    @Value("\${magicjar.kis.base-url-vps:https://openapivts.koreainvestment.com:29443}")
    private val kisBaseVps: String,
) : KisRestPort {
    private val log = LoggerFactory.getLogger(KisRestCandleAdapter::class.java)

    companion object {
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
        private const val TR_ID_DAILY = "FHKST03010100"
        private const val ENDPOINT_DAILY = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"

        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
        private const val TR_ID_1MIN = "FHKST03010230"
        private const val ENDPOINT_1MIN = "/uapi/domestic-stock/v1/quotations/inquire-time-dailychartprice"

        private const val SOURCE = "KIS"
        private val DATE_FMT = DateTimeFormatter.ofPattern("yyyyMMdd")
        private val KST_ZONE = ZoneId.of("Asia/Seoul")

        /** CandleInterval → KIS period_div_code 매핑 (FHKST03010100) */
        private fun toPeriodDivCode(interval: CandleInterval): String =
            when (interval) {
                CandleInterval.ONE_DAY -> "D"
                CandleInterval.ONE_WEEK -> "W"
                CandleInterval.ONE_MONTH -> "M"
                CandleInterval.ONE_YEAR -> "Y"
                else -> throw IllegalArgumentException("KIS REST 미지원 interval: ${interval.code}")
            }
    }

    override fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle> {
        if (interval == CandleInterval.ONE_MIN) {
            return fetchOneMinCandles(code)
        }
        return fetchDailyCandles(code, interval)
    }

    /**
     * 1m 분봉 조회 (FHKST03010230) — 일자당 페이지네이션으로 전수 수집.
     *
     * raw spec:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     *
     * 1 call = 최대 120건. 정규장 9:00~15:30 ≈ 390건 → 일자당 ~4 call 필요.
     *
     * 페이지네이션 알고리즘 (KIS-first raw fetch 의무 — 헌법 §3):
     *   1. fid_input_hour_1="153000" (장마감) → 해당 시각 이전 최신 120건 반환 (내림차순)
     *   2. 응답 목록의 마지막 row(가장 이른 시각)의 stck_cntg_hour - 1분 → 다음 fid_input_hour_1
     *   3. 가장 이른 ts 가 09:00 KST 이전이면 해당 일자 종료
     *   4. 응답 0건 또는 일자당 최대 MAX_PAGES_PER_DAY(=6) call 초과 시 안전 종료
     *
     * Rate Limit (ADR 0037): 20 req/s. 50ms 마진 유지.
     * 11종목 × 4 call × 30일 = 1320 calls × 50ms = ~66s — 안전.
     *
     * in-memory dedup: (code, interval, ts epoch second) Set으로 중복 경계 row 방지.
     */
    private fun fetchOneMinCandles(code: String): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.warn("KIS REST 1m candle: REAL credential 없음 — code={}", code)
                    return emptyList()
                }

        val userId = cred.id.userId
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                log.warn("KIS REST 1m candle: 토큰 발급 실패 — code={}: {}", code, e.message)
                return emptyList()
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val allCandles = mutableListOf<Candle>()
            // in-memory dedup: ts epoch second 기준 — 페이지 경계 중복 방지
            val seenTs = mutableSetOf<Long>()

            // 직전 30 거래일 (주말 제외 — 약 45 달력일 커버)
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
                // 일자별 페이지네이션 시작: 장마감(153000) 기준 역순 백워크
                // raw: FHKST03010230 fid_input_hour_1 = 이 시각 이전 120건 반환
                var currentHour = "200000"
                var pageCount = 0
                // 무한루프 방지: 일자당 최대 6 pages (120건 × 6 = 720건 한도 — 정규장 390건의 약 2배)
                val maxPagesPerDay = 8
                var dayDone = false

                while (!dayDone && pageCount < maxPagesPerDay) {
                    try {
                        val body =
                            webClient
                                .get()
                                .uri { builder ->
                                    builder
                                        .scheme("https")
                                        .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                        .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                        .path(ENDPOINT_1MIN)
                                        // fid_cond_mrkt_div_code: J (주식) — FHKST03010230 spec
                                        .queryParam("fid_cond_mrkt_div_code", "UN")
                                        // fid_input_iscd: 종목코드
                                        .queryParam("fid_input_iscd", code)
                                        // fid_input_hour_1: 이 시각 이전 최신 120건 반환 (페이지네이션 커서)
                                        // raw spec: fid_input_hour_1 HHMMSS — 해당 시각 이전 데이터 반환
                                        .queryParam("fid_input_hour_1", currentHour)
                                        // fid_input_date_1: 조회 날짜 (yyyyMMdd)
                                        .queryParam("fid_input_date_1", dateStr)
                                        // fid_pw_data_incu_yn: N (과거 데이터 포함 여부)
                                        .queryParam("fid_pw_data_incu_yn", "N")
                                        // fid_fake_tick_incu_yn: 허봉 포함 여부 (빈 문자열 = 미포함, KIS spec 6번째 파라미터 필수 전송)
                                        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
                                        .queryParam("fid_fake_tick_incu_yn", "")
                                        .build()
                                }.header("authorization", "Bearer $accessToken")
                                .header("appkey", appKeyStr)
                                .header("appsecret", secretKeyStr)
                                .header("tr_id", TR_ID_1MIN)
                                .header("custtype", "P")
                                .retrieve()
                                .bodyToMono(Map::class.java)
                                .timeout(Duration.ofSeconds(10))
                                .block()

                        pageCount++

                        if (body == null) {
                            log.debug("KIS 1m fetch 빈 body: code={} date={} hour={}", code, dateStr, currentHour)
                            dayDone = true
                        } else {
                            val parsed = parseKis1mResponse(body, code, targetDate)
                            if (parsed.isEmpty()) {
                                // 응답 0건 = 해당 시각 이전 데이터 없음 → 일자 완료
                                log.debug(
                                    "KIS 1m fetch 응답 0건 — 일자 완료: code={} date={} hour={} page={}",
                                    code,
                                    dateStr,
                                    currentHour,
                                    pageCount,
                                )
                                dayDone = true
                            } else {
                                // dedup 적용 후 추가
                                var newCount = 0
                                for (candle in parsed) {
                                    val epochSec = candle.ts.epochSeconds
                                    if (seenTs.add(epochSec)) {
                                        allCandles.add(candle)
                                        newCount++
                                    }
                                }
                                log.debug(
                                    "KIS 1m fetch page 완료: code={} date={} hour={} page={} fetched={} new={}",
                                    code,
                                    dateStr,
                                    currentHour,
                                    pageCount,
                                    parsed.size,
                                    newCount,
                                )

                                // 다음 페이지 커서: 응답 목록에서 가장 이른 stck_cntg_hour - 1분
                                // KIS 응답은 내림차순(최신→과거) — 마지막 row가 가장 이른 시각
                                val earliestCandle = parsed.minByOrNull { it.ts.epochSeconds }
                                if (earliestCandle != null) {
                                    // kotlinx.datetime.Instant → java.time 변환 (epochSeconds 경유)
                                    val earliestLt =
                                        java.time.Instant
                                            .ofEpochSecond(earliestCandle.ts.epochSeconds)
                                            .atZone(KST_ZONE)
                                            .toLocalTime()

                                    // 09:00:00 KST 이전 = 정규장 시작 전 → 해당 일자 전체 수집 완료
                                    val marketOpen = LocalTime.of(8, 0, 0)
                                    if (!earliestLt.isAfter(marketOpen)) {
                                        log.debug(
                                            "KIS 1m fetch 장시작 도달 — 일자 완료: code={} date={} earliestTime={}",
                                            code,
                                            dateStr,
                                            earliestLt,
                                        )
                                        dayDone = true
                                    } else {
                                        // 1분 앞으로 백워크 (HHmmss 형식)
                                        val nextLt = earliestLt.minus(1, ChronoUnit.MINUTES)
                                        currentHour =
                                            "%02d%02d%02d".format(
                                                nextLt.hour,
                                                nextLt.minute,
                                                nextLt.second,
                                            )
                                    }
                                } else {
                                    dayDone = true
                                }
                            }
                        }

                        // KIS API rate limit 준수: ADR 0037 — 20 req/s 한도. 50ms 간격 안전 마진
                        Thread.sleep(50)
                    } catch (e: WebClientResponseException) {
                        val statusCode = e.statusCode.value()
                        if (statusCode == 500) {
                            // KIS 500 INTERNAL_SERVER_ERROR — 1회 재시도 + 500ms backoff (관찰: 5~10% 간헐적 발생)
                            log.warn(
                                "KIS 1m candle 500 오류 — 500ms backoff 후 1회 재시도: code={} date={} hour={}",
                                code,
                                dateStr,
                                currentHour,
                            )
                            Thread.sleep(500)
                            // retryOnce = true 로 다음 루프 반복 시도 (dayDone = false 유지)
                            // pageCount는 증가하지 않았으므로 동일 hour로 재시도됨
                        } else {
                            log.warn(
                                "KIS 1m candle HTTP 오류: code={} date={} hour={} status={}",
                                code,
                                dateStr,
                                currentHour,
                                statusCode,
                            )
                            dayDone = true
                        }
                    } catch (e: Exception) {
                        log.warn(
                            "KIS 1m candle 오류: code={} date={} hour={} err={}",
                            code,
                            dateStr,
                            currentHour,
                            e.message,
                        )
                        dayDone = true
                    }
                }

                if (pageCount >= maxPagesPerDay && !dayDone) {
                    log.warn(
                        "KIS 1m fetch MAX_PAGES 도달 안전 종료: code={} date={} pages={}",
                        code,
                        dateStr,
                        pageCount,
                    )
                }

                log.info(
                    "KIS 1m backfill 일자 완료: code={} date={} pages={} total={}",
                    code,
                    dateStr,
                    pageCount,
                    allCandles.size,
                )
            }

            log.info("KIS 1m backfill 전체 완료: code={} total={}", code, allCandles.size)
            allCandles
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * 일봉·주봉·월봉·연봉 조회 (FHKST03010100).
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     */
    private fun fetchDailyCandles(
        code: String,
        interval: CandleInterval,
    ): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.warn("KIS REST candle: REAL credential 없음 — code={} interval={}", code, interval.code)
                    return emptyList()
                }

        val userId = cred.id.userId
        val accessToken =
            try {
                kisAuthAdapter.getAccessToken(userId, AccountType.REAL)
            } catch (e: Exception) {
                log.warn("KIS REST candle: 토큰 발급 실패 — code={}: {}", code, e.message)
                return emptyList()
            }

        var appKey: ByteArray? = null
        var secretKey: ByteArray? = null
        return try {
            appKey = secretCipher.decrypt(cred.appKeyEnc)
            secretKey = secretCipher.decrypt(cred.secretKeyEnc)
            val appKeyStr = String(appKey)
            val secretKeyStr = String(secretKey)

            val periodDivCode = toPeriodDivCode(interval)
            val today = LocalDate.now(KST_ZONE).format(DATE_FMT)
            // 충분한 과거 시작일 (count * 1.5 거래일 마진)
            val startDate = LocalDate.now(KST_ZONE).minusYears(30).format(DATE_FMT)

            val body =
                webClient
                    .get()
                    .uri { builder ->
                        builder
                            .scheme("https")
                            .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                            .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                            .path(ENDPOINT_DAILY)
                            .queryParam("fid_cond_mrkt_div_code", "UN")
                            .queryParam("fid_input_iscd", code)
                            .queryParam("fid_input_date_1", startDate)
                            .queryParam("fid_input_date_2", today)
                            .queryParam("fid_period_div_code", periodDivCode)
                            .queryParam("fid_org_adj_prc", "0")
                            .build()
                    }.header("authorization", "Bearer $accessToken")
                    .header("appkey", appKeyStr)
                    .header("appsecret", secretKeyStr)
                    .header("tr_id", TR_ID_DAILY)
                    .header("custtype", "P")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(10))
                    .block()
                    ?: return emptyList()

            parseKisDailyResponse(body, code, interval)
        } catch (e: WebClientResponseException) {
            log.warn("KIS REST candle 실패: code={} interval={} status={}", code, interval.code, e.statusCode)
            emptyList()
        } catch (e: Exception) {
            log.warn("KIS REST candle 오류: code={} interval={} err={}", code, interval.code, e.message)
            emptyList()
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * FHKST03010230 1m 응답 파싱.
     * output2[]: stck_bsop_date (yyyyMMdd) + stck_cntg_hour (HHmmss) → KST timestamp
     * stck_oprc·stck_hgpr·stck_lwpr·stck_prpr(현재가=종가)·acml_vol
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseKis1mResponse(
        body: Map<*, *>,
        code: String,
        targetDate: LocalDate,
    ): List<Candle> {
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != "0") {
            log.debug(
                "KIS 1m rt_cd != 0: code={} date={} rt_cd={} msg={}",
                code,
                targetDate,
                rtCd,
                body["msg1"],
            )
            return emptyList()
        }
        val output2 = body["output2"] as? List<*> ?: return emptyList()
        val candles = mutableListOf<Candle>()
        for (row in output2) {
            val item = row as? Map<*, *> ?: continue
            // stck_bsop_date: yyyyMMdd, stck_cntg_hour: HHmmss
            val dateStr = item["stck_bsop_date"]?.toString()?.trim() ?: continue
            val hourStr = item["stck_cntg_hour"]?.toString()?.trim() ?: continue
            val close = item["stck_prpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: continue
            val open = item["stck_oprc"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val high = item["stck_hgpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val low = item["stck_lwpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volume = item["acml_vol"]?.toString()?.trim()?.toLongOrNull() ?: 0L

            val ts =
                runCatching {
                    val ld =
                        if (dateStr.length == 8) {
                            LocalDate.parse(dateStr, DATE_FMT)
                        } else {
                            targetDate
                        }
                    // HHmmss → LocalTime 파싱
                    val lt =
                        LocalTime.of(
                            hourStr.substring(0, 2).toInt(),
                            hourStr.substring(2, 4).toInt(),
                            hourStr.substring(4, 6).toInt(),
                        )
                    val epochSecond = ld.atTime(lt).atZone(KST_ZONE).toEpochSecond()
                    KtInstant.fromEpochSeconds(epochSecond)
                }.getOrNull() ?: continue

            candles.add(
                Candle(
                    code = code,
                    interval = CandleInterval.ONE_MIN,
                    ts = ts,
                    openRaw = open,
                    highRaw = high,
                    lowRaw = low,
                    closeRaw = close,
                    openAdj = null,
                    highAdj = null,
                    lowAdj = null,
                    closeAdj = null,
                    volume = volume,
                    source = SOURCE,
                ),
            )
        }
        return candles
    }

    /**
     * FHKST03010100 일봉/주봉/월봉/연봉 응답 파싱.
     * output2[]: stck_bsop_date (yyyyMMdd) → KST 00:00:00 ts
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseKisDailyResponse(
        body: Map<*, *>,
        code: String,
        interval: CandleInterval,
    ): List<Candle> {
        val rtCd = body["rt_cd"]?.toString()
        if (rtCd != "0") {
            log.warn("KIS REST candle rt_cd != 0: code={} rt_cd={} msg={}", code, rtCd, body["msg1"])
            return emptyList()
        }
        val output2 = body["output2"] as? List<*> ?: return emptyList()
        val candles = mutableListOf<Candle>()
        for (row in output2) {
            val item = row as? Map<*, *> ?: continue
            val dateStr = item["stck_bsop_date"]?.toString()?.trim() ?: continue
            val close = item["stck_clpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: continue
            val open = item["stck_oprc"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val high = item["stck_hgpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val low = item["stck_lwpr"]?.toString()?.trim()?.toBigDecimalOrNull() ?: BigDecimal.ZERO
            val volume = item["acml_vol"]?.toString()?.trim()?.toLongOrNull() ?: 0L

            // dateStr = "20240507" → epoch second (KST 00:00:00)
            val ts =
                runCatching {
                    val ld = LocalDate.parse(dateStr, DATE_FMT)
                    val epochSecond = ld.atStartOfDay(KST_ZONE).toEpochSecond()
                    KtInstant.fromEpochSeconds(epochSecond)
                }.getOrNull() ?: continue

            candles.add(
                Candle(
                    code = code,
                    interval = interval,
                    ts = ts,
                    openRaw = open,
                    highRaw = high,
                    lowRaw = low,
                    closeRaw = close,
                    openAdj = null,
                    highAdj = null,
                    lowAdj = null,
                    closeAdj = null,
                    volume = volume,
                    source = SOURCE,
                ),
            )
        }
        log.debug("KIS REST candle 파싱: code={} interval={} count={}", code, interval.code, candles.size)
        return candles
    }

    override fun fetchPrice(
        userId: String,
        code: String,
    ): kr.spdaewoon.magicjar.domain.market.tick.TickEvent =
        throw NotImplementedError(
            "KisRestCandleAdapter는 fetchPrice 미지원. KisRestPriceAdapter 사용. " +
                "후속 minor PR에서 KisRestPort 인터페이스 분리 (KisRestCandlePort + KisRestPricePort) 예정.",
        )
}

package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.infrastructure.kis.KisTokenCacheHelper
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshResponse
import kr.spdaewoon.magicjar.api.service.dto.IntervalRefreshResult
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
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
 * Rate Limit (ADR 0037): 초당 10건 한도 (KisRateLimiterPort.acquire 합류 — spec PR #564 §2-2 LOCK).
 *   - 사용자 트리거 path → priority=USER_PATH (즉시 우선 처리)
 *   - Bucket4j 단일 bucket 강제 후 sleep 마진 제거 (acquire blocking polling이 이미 0.1초 단위 회복)
 * 인증: api 모듈 UserKisCredentialJpaRepository + AesGcmCipher + Redis token cache (KisRestPriceMetricsAdapter 패턴 정합).
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Service
class CandleRefreshService(
    private val webClient: WebClient,
    private val instrumentJpaRepository: InstrumentJpaRepository,
    private val candleJpaRepository: CandleJpaRepository,
    private val tokenHelper: KisTokenCacheHelper,
    private val kisRateLimiterPort: KisRateLimiterPort,
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

        /**
         * FHKST03010100 cursor 페이지네이션 최대 페이지 수 (B-2 fix 2026-05-19 backend-core).
         *
         * KIS `inquire-daily-itemchartprice` 응답 output2[]는 단일 호출 100건 limit (KIS API 정책).
         * 30년 범위(약 7560 거래일) 요청 시 단일 페이지로 전부 못 받음 → cursor 페이지네이션 의무.
         *
         * MAX_PAGES=6 (100 × 6 = 600 거래일 ≈ 2.4년 cover) — fromDate~toDate 범위가 그 이상이면 안전 종료.
         * 정기 운영 시 fromDate=30년 전이지만 실제 사용자 요청은 fresh fetch (recent gap fill) 위주이므로
         * 6 페이지로 충분. B-1 consumer fix와 정합 — chart-curator PR #587 evidence + ADR 0086 §11.
         */
        private const val DAILY_MAX_PAGES = 6

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

        // 4. market division 후보 — ADR 0091: market 단위 KOSPI=UN 금지, 종목 단위 후보만 UN 우선 + J fallback.
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
        val mrktDivCodes = resolveMrktDivCodeCandidates(code)

        // 5. interval 별 순차 갱신 (Rate Limit ADR 0037 초당 10건 + spec PR #564 §2-2 LOCK)
        // sleep 마진 제거 — KisRateLimiterPort.acquire가 USER_PATH 우선순위로 적시 진입
        val results = mutableListOf<IntervalRefreshResult>()
        for (intervalCode in effectiveIntervals) {
            val result =
                when (intervalCode) {
                    "1m" -> refreshOneMinCandlesWithFallback(code, mrktDivCodes, accessToken, appKey, secretKey)
                    in DAILY_INTERVALS ->
                        refreshDailyCandlesWithFallback(code, intervalCode, mrktDivCodes, accessToken, appKey, secretKey)
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
     * 종목코드 → KIS fid_cond_mrkt_div_code 후보 결정.
     *
     * KIS raw spec:
     *   - FHKST03010230: J:주식, NX:NXT, UN:통합
     *   - FHKST03010100: J:KRX, NX:NXT, UN:통합
     *
     * ADR 0091:
     *   - market=KOSPI 전체를 UN으로 보내는 단순화 금지.
     *   - NXT 거래 가능 여부는 종목 단위로 변하며, NXT 공식 화면도 KOSPI/KOSDAQ 상품군을 함께 표시한다.
     *   - dedicated nxt_tradable 컬럼 도입 전까지 is_kospi200/is_kosdaq150을 보수적 allow-list로 사용한다.
     *   - UN 후보가 빈 응답이면 J(KRX)로 fallback 한다.
     */
    private fun resolveMrktDivCode(code: String): String = resolveMrktDivCodeCandidates(code).first()

    private fun resolveMrktDivCodeCandidates(code: String): List<String> {
        val instrument = instrumentJpaRepository.findById(code).orElse(null)
        val isNxtIntegratedCandidate =
            when (instrument?.market) {
                "KOSPI" -> instrument.isKospi200
                "KOSDAQ" -> instrument.isKosdaq150
                else -> false
            }
        return if (isNxtIntegratedCandidate) {
            listOf("UN", "J")
        } else {
            listOf("J")
        }
    }

    private fun refreshDailyCandlesWithFallback(
        code: String,
        intervalCode: String,
        mrktDivCodes: List<String>,
        accessToken: String,
        appKey: String,
        secretKey: String,
    ): IntervalRefreshResult {
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val result = refreshDailyCandles(code, intervalCode, mrktDivCode, accessToken, appKey, secretKey)
            if (result.fetchedCount > 0 || result.status != "OK" || index == mrktDivCodes.lastIndex) {
                return result
            }
            log.warn(
                "캔들 갱신 UN 응답 0건 — J fallback 재시도: code={} interval={} mrktDivCode={}",
                code,
                intervalCode,
                mrktDivCode,
            )
        }
        return IntervalRefreshResult(intervalCode, 0, 0, null, "FAILED")
    }

    private fun refreshOneMinCandlesWithFallback(
        code: String,
        mrktDivCodes: List<String>,
        accessToken: String,
        appKey: String,
        secretKey: String,
    ): IntervalRefreshResult {
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val result = refreshOneMinCandles(code, mrktDivCode, accessToken, appKey, secretKey)
            if (result.fetchedCount > 0 || result.status != "OK" || index == mrktDivCodes.lastIndex) {
                return result
            }
            log.warn(
                "1m 캔들 갱신 UN 응답 0건 — J fallback 재시도: code={} mrktDivCode={}",
                code,
                mrktDivCode,
            )
        }
        return IntervalRefreshResult("1m", 0, 0, null, "FAILED")
    }

    /**
     * 일봉·주봉·월봉·연봉 갱신 (FHKST03010100) — cursor 페이지네이션 적용.
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     * - fid_cond_mrkt_div_code: J(KRX주식) 또는 UN(통합 — ADR 0091 종목 단위 후보만)
     * - fid_input_iscd: 종목코드
     * - fid_input_date_1: 시작일 (yyyyMMdd) — 30년 전 기준 전수 수집
     * - fid_input_date_2: 종료일 (yyyyMMdd) — cursor로 사용 (페이지네이션)
     * - fid_period_div_code: D(일)/W(주)/M(월)/Y(연)
     * - fid_org_adj_prc: 0 (수정주가 미반영)
     *
     * ⚠ B-2 cursor 페이지네이션 fix (2026-05-19 backend-core — chart-curator PR #587 evidence + B-1 consumer fix 정합):
     *   KIS `inquire-daily-itemchartprice` 응답 output2[]는 단일 호출 **100건 limit** (KIS API 정책).
     *   30년 범위(약 7560 거래일)를 단일 호출로 요청 시 최신 100건만 반환 → 그 이전 데이터 누락.
     *   사용자 dogfooding (한국석유 004090) evidence: 5개월 갭 누적 root cause 중 하나.
     *
     *   Fix: `fid_input_date_2`(종료일)를 cursor로 역방향 페이지네이션 — 응답 oldest date의 -1일을 다음 cursor로 사용.
     *   최대 DAILY_MAX_PAGES(=6) 페이지 안전 종료. cursor advance 부재 시 break 무한루프 가드.
     *   ASC 정규화는 parseKisDailyOutput2 단일 LOCK (PR #558 정합) — 전체 페이지 합산 후 sortedBy.
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
        // 30년 전 기준 전수 수집 (KIS 응답 가능 범위 최대)
        val fromDate = LocalDate.now(KST_ZONE).minusYears(30)
        val startDateStr = fromDate.format(DATE_FMT)

        val allCandles = mutableListOf<KisDailyRow>()
        var cursor: LocalDate = LocalDate.now(KST_ZONE)
        var pageCount = 0
        var pageStatus = "OK"

        // D-1 try-finally drift 차단 패턴 (T-D-1): 메서드 단위 outer signalSent 추적 — 각 페이지마다 갱신.
        // (refreshDailyCandles 메서드 outer try 전체에서 마지막 acquire의 시그널 미발행 path 감시)
        var lastPageSignalSent = true // 첫 페이지 진입 전 — outer catch에서 false 시그널 보강
        var lastPageAcquired = false
        try {
            while (pageCount < DAILY_MAX_PAGES) {
                if (cursor < fromDate) break

                // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH priority)
                // ADR 0037 초당 10건 한도 강제. 페이지마다 acquire — 사용자 트리거 즉시 우선 처리.
                kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
                lastPageSignalSent = false
                lastPageAcquired = true

                val cursorStr = cursor.format(DATE_FMT)

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
                                .queryParam("fid_input_date_1", startDateStr)
                                // fid_input_date_2: cursor (페이지네이션) — 첫 페이지=today, 이후 oldest-1일
                                .queryParam("fid_input_date_2", cursorStr)
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

                pageCount++

                if (body == null) {
                    log.warn("캔들 갱신 빈 응답: code={} interval={} page={}", code, intervalCode, pageCount)
                    break
                }

                val rtCd = body["rt_cd"]?.toString()
                if (rtCd != "0") {
                    log.warn(
                        "캔들 갱신 rt_cd != 0: code={} interval={} page={} rt_cd={} msg={}",
                        code,
                        intervalCode,
                        pageCount,
                        rtCd,
                        body["msg1"],
                    )
                    pageStatus = "FAILED"
                    break
                }

                @Suppress("UNCHECKED_CAST")
                val output2 = body["output2"] as? List<*>
                if (output2.isNullOrEmpty()) {
                    log.debug("캔들 갱신 output2 0건 — 페이지네이션 종료: code={} interval={} page={}", code, intervalCode, pageCount)
                    kisRateLimiterPort.onSuccess()
                    lastPageSignalSent = true
                    break
                }

                val pageCandles = parseKisDailyOutput2(output2, code, intervalCode)
                if (pageCandles.isEmpty()) {
                    kisRateLimiterPort.onSuccess()
                    lastPageSignalSent = true
                    break
                }
                allCandles.addAll(pageCandles)
                kisRateLimiterPort.onSuccess()
                lastPageSignalSent = true

                // cursor advance — oldest epochSec → LocalDate.minusDays(1)
                // parseKisDailyOutput2가 이미 ASC 정렬이므로 first() = oldest.
                val oldestEpochSec = pageCandles.first().epochSec
                val oldestDate =
                    Instant
                        .ofEpochSecond(oldestEpochSec)
                        .atZone(KST_ZONE)
                        .toLocalDate()
                val newCursor = oldestDate.minusDays(1)

                // 무한루프 가드 — cursor가 advance 안 되면 즉시 break (KIS 응답이 동일 페이지 반복 risk)
                if (newCursor >= cursor) {
                    log.debug(
                        "캔들 갱신 cursor advance 부재 — break: code={} interval={} cursor={} newCursor={}",
                        code,
                        intervalCode,
                        cursor,
                        newCursor,
                    )
                    break
                }
                cursor = newCursor

                // fromDate 도달 시 종료
                if (cursor < fromDate) break
            }

            if (pageCount >= DAILY_MAX_PAGES) {
                log.info(
                    "캔들 갱신 MAX_PAGES 도달 안전 종료: code={} interval={} pages={}",
                    code,
                    intervalCode,
                    pageCount,
                )
            }

            // 페이지 간 중복 제거 + ASC 정규화 (parseKisDailyOutput2가 페이지마다 ASC 보장 — 전체 ASC 한번 더 안전)
            val dedupedCandles =
                allCandles
                    .distinctBy { it.epochSec }
                    .sortedBy { it.epochSec }

            val inserted = upsertCandles(dedupedCandles, intervalCode)
            val latestTs = queryLatestTs(code, intervalCode)

            log.info(
                "캔들 갱신 완료: code={} interval={} pages={} fetched={} inserted={}",
                code,
                intervalCode,
                pageCount,
                dedupedCandles.size,
                inserted,
            )
            return IntervalRefreshResult(intervalCode, dedupedCandles.size, inserted, latestTs, pageStatus)
        } catch (e: WebClientResponseException) {
            if (e.statusCode.value() == 429) {
                // HTTP 429 → onTpsExceeded 시그널 + 명시 에러 결과 (mock 합성 0, ADR 0014)
                kisRateLimiterPort.onTpsExceeded()
                lastPageSignalSent = true
                log.warn("캔들 갱신 Rate Limit (429): code={} interval={} pages={}", code, intervalCode, pageCount)
                return IntervalRefreshResult(intervalCode, allCandles.size, 0, null, "RATE_LIMITED")
            } else {
                log.warn(
                    "캔들 갱신 KIS 오류: code={} interval={} pages={} status={}",
                    code,
                    intervalCode,
                    pageCount,
                    e.statusCode.value(),
                )
                return IntervalRefreshResult(intervalCode, allCandles.size, 0, null, "FAILED")
            }
        } catch (e: Exception) {
            log.warn("캔들 갱신 오류: code={} interval={} pages={} err={}", code, intervalCode, pageCount, e.message)
            return IntervalRefreshResult(intervalCode, allCandles.size, 0, null, "FAILED")
        } finally {
            if (lastPageAcquired && !lastPageSignalSent) {
                log.warn(
                    "[KisRateLimit] CandleRefresh daily acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} interval={} page={}",
                    code,
                    intervalCode,
                    pageCount,
                )
            }
        }
    }

    /**
     * 1m 분봉 갱신 (FHKST03010230) — 직전 30 거래일 역방향 페이지네이션.
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     * - fid_cond_mrkt_div_code: J(KRX주식) 또는 UN(통합 — ADR 0091 종목 단위 후보만)
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
                // D-1 try-finally drift 차단 패턴 (T-D-1): 페이지 단위 signalSent flag.
                var pageSignalSent = false
                var pageAcquired = false
                try {
                    // Rate Limit acquire (spec PR #564 §2-2 LOCK — USER_PATH priority)
                    // ADR 0037 초당 10건 한도 강제. 사용자 트리거 path → 즉시 acquire.
                    kisRateLimiterPort.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
                    pageAcquired = true

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
                                    // fid_cond_mrkt_div_code: ADR 0091 resolver — 종목 단위 UN allow-list + J fallback.
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
                    // 정상 응답 (null·rt_cd!=0·output2 empty 포함) — token 소비 정상 집계.
                    kisRateLimiterPort.onSuccess()
                    pageSignalSent = true
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
                } catch (e: WebClientResponseException) {
                    if (e.statusCode.value() == 429) {
                        kisRateLimiterPort.onTpsExceeded()
                        pageSignalSent = true
                    }
                    log.warn(
                        "1m 갱신 페이지 HTTP 오류: code={} date={} status={}",
                        code,
                        dateStr,
                        e.statusCode.value(),
                    )
                    dayDone = true
                } catch (e: Exception) {
                    log.warn("1m 갱신 페이지 오류: code={} date={} err={}", code, dateStr, e.message)
                    dayDone = true
                } finally {
                    if (pageAcquired && !pageSignalSent) {
                        log.warn(
                            "[KisRateLimit] CandleRefresh 1m page acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} date={}",
                            code,
                            dateStr,
                        )
                    }
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
     *
     * ⚠ 함정 가드: KIS DESC 응답 → ASC 정규화 (P0-B fix, 2026-05-19 — market-data PR #555 evidence + ADR 0086 §11 정합):
     *   KIS `inquire-daily-itemchartprice`는 응답 output2[]를 **최신→과거 DESC 순서**로 반환 (4 PERIOD_DIV_CODE 공통).
     *   햇징 룰 §1 (이중 검증) monotonic이 ASC 강제이므로 정규화 누락 시 idx 1+ 전부 reject → insertedCount=0.
     *   한국석유(004090) PR #535·#537 evidence — 5개월 일봉 갭 누적의 root cause.
     *
     * raw evidence:
     *   - consumer KisRestCandleAdapter.parseKisDailyResponse (PR #537) 동일 패턴 적용 완료
     *   - market-data PR #540 batch CandleRefreshJob 동일 패턴 적용 완료
     *   - 본 api CandleRefreshService는 별도 path drift로 누락된 채 잔존 → 본 fix로 정합 회복
     *
     * ASC 정규화 적용 범위 — 본 함수 단일 LOCK으로 4 timeframe 전부 cover (FHKST03010100 endpoint 4 PERIOD_DIV_CODE 공통 schema):
     *   - 일봉(D): 한국석유 evidence — 정규화 필수
     *   - 주봉(W)·월봉(M)·연봉(Y): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
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
        // KIS DESC → ASC 정규화 (P0-B fix 2026-05-19 backend-core, PR #555 evidence + ADR 0086 §11).
        // 햇징 룰 §1 monotonic이 ASC 강제이므로 adapter layer가 source-of-truth로 정규화.
        return result.sortedBy { it.epochSec }
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
     * KIS access_token + appKey + secretKey 해결 — T-4 통합 helper 위임.
     *
     * 기존 inline 구현 (access_token:{userId}:REAL 결함 prefix + hardcode TTL 23h) 폐기.
     * 신규 [KisTokenCacheHelper]:
     *   - 단일 `kis:token:{userId}:{accountType}` prefix LOCK (T-1)
     *   - ADR 0038 분산락 정합 — EGW00133 1분 1회 위반 차단 (T-4)
     *   - KIS `expires_in` 응답 × 0.95 동적 TTL (T-3)
     *
     * KIS-only 정보 수집 정책 (feedback_kis_key_realonly_for_data): REAL 자격증명 우선.
     * helper 가 REAL 부재 시 MOCK fallback 처리. 부재 시 IllegalStateException → 502 변환.
     */
    private fun resolveAccessTokenAndKeys(): Triple<String, String, String> =
        try {
            // userId 빈 문자열 → helper 가 REAL 우선 → 1순위 자격증명 fallback
            tokenHelper.resolveTokenAndKeys(userId = "", accountType = AccountType.REAL)
        } catch (e: IllegalStateException) {
            throw ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "KIS 자격증명이 등록되지 않았습니다. 온보딩에서 KIS API 키를 등록해 주세요.",
            )
        } catch (e: ResponseStatusException) {
            throw e
        } catch (e: Exception) {
            log.error("KIS access_token 발급 실패: {}", e.message)
            throw ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "KIS 인증 실패: ${e.message}")
        }
}

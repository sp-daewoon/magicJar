package kr.spdaewoon.magicjar.consumer.infrastructure.kis

import kr.spdaewoon.magicjar.consumer.persistence.ConsumerInstrumentJpaRepository
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerUserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.KisRestPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
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
 *   - 1d·1wk·1mo·1y: FHKST03010100 (inquire-daily-itemchartprice) — 4 PERIOD_DIV_CODE 공통 endpoint
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
 *   - 1m: FHKST03010230 (inquire-time-dailychartprice) — 최대 120건/call, 1일치 분봉
 *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
 *
 * Yahoo Finance 의존성 전면 제거 (2026-05-11 — KIS-only 정책 적용).
 * 룰 5 위반 금지: LLM provider import 0건.
 *
 * KIS 500 retry 정책 (§33 — 2026-05-12):
 *   KIS 외부 결함 일시적 부하 통과를 위해 HTTP 500 INTERNAL_SERVER_ERROR 발생 시
 *   최대 3회 exponential backoff (500ms → 1000ms → 2000ms) 재시도.
 *   503/502 등 기타 HTTP 오류는 즉시 실패 (서비스 점검 가능성, 재시도 무의미).
 *   Rate Limit (ADR 0037): retry는 500 발생 시에만 — 초당 10건 한도 영향 미미.
 *
 * ⚠ KIS raw 사전 ASC 정규화 매트릭스 (ADR 0086 §11 LOCK — chart-curator skill §5 source-of-truth 정합):
 *   KIS 응답 sort order는 timeframe·endpoint별로 일관되지 않음. adapter layer가 raw 정규화 단일 책임.
 *
 *   | parse 함수 | tr_id | cover timeframe | sort 패턴 | ASC fix |
 *   |-----------|-------|----------------|-----------|---------|
 *   | parseKisDailyResponse | FHKST03010100 | 일봉(D)·주봉(W)·월봉(M)·연봉(Y) 4 PERIOD_DIV_CODE | DESC | PR #537 |
 *   | parseKis1mResponse | FHKST03010230 | 1분봉 (1일치 페이지네이션) | DESC 가능 | PR #537 |
 *
 *   매트릭스 LOCK: 4 timeframe(daily) + 1 timeframe(1m) = **본 어댑터 cover 5 timeframe 전부**가
 *   2 parse 함수 단일 LOCK으로 ASC 정규화 cover. 향후 추가 timeframe(예: 시간외 FHPST02320000)은
 *   별도 parse 함수 추가 + ADR 0086 §11 매트릭스 행 추가 + 본 KDoc 갱신 의무.
 *
 *   evidence: 한국석유(004090) PR #535·#537·#N — KIS DESC 응답 + sanity check reject → 5개월 갭 누적.
 */
@Component
@Primary
class KisRestCandleAdapter(
    private val webClient: WebClient,
    private val kisAuthAdapter: KisAuthAdapter,
    private val secretCipher: SecretCipher,
    private val credentialRepository: ConsumerUserKisCredentialJpaRepository,
    private val instrumentRepository: ConsumerInstrumentJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
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

        /**
         * KIS HTTP 500 exponential backoff 대기 시간 (ms).
         * §33: 3회 재시도 — 500ms → 1000ms → 2000ms.
         * Rate Limit (ADR 0037): retry는 500 발생 시에만 발동 — 초당 10건 한도 영향 미미.
         */
        private val RETRY_BACKOFF_MS = listOf(500L, 1000L, 2000L)
        private const val RETRY_MAX_ATTEMPTS = 3

        /**
         * KIS inquire-daily-itemchartprice (FHKST03010100) 1회 호출 최대 봉 수.
         *
         * KIS-first raw spec (헌법 §3 의무 인용):
         *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
         *   docstring: "한 번의 호출에 최대 100건"
         *
         * 4 PERIOD_DIV_CODE(D/W/M/Y) 공통 한도. 페이지네이션 미적용 시 30년 backfill 의도가
         * 최근 100건(일봉 약 5개월·주봉 약 2년·월봉 약 8년·연봉 100년)으로 잘림 → regression.
         */
        const val KIS_PAGE_LIMIT: Int = 100

        /**
         * 페이지네이션 안전 한도 — 무한 loop·과도한 호출 방지.
         *
         * PR 2cbd4e8 검증 패턴 (한국석유 004090 1년 backfill 243봉 = 3 pages):
         *   - 일봉(D): 6 pages × 100 = 600봉 ≈ 약 2.5년 cover
         *   - 주봉(W): 6 pages × 100 = 600봉 ≈ 약 11년 cover
         *   - 월봉(M): 6 pages × 100 = 600봉 ≈ 약 50년 cover
         *   - 연봉(Y): 6 pages × 100 = 600봉 ≈ 600년 cover (사실상 무제한)
         *
         * Rate Limit (ADR 0037 초당 10건): 6 calls × ~100ms = 600ms — 한도 영향 미미.
         */
        const val MAX_PAGES: Int = 6

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

    /**
     * 다음 페이지 cursor 산정 — `oldestDate - 1day` 단순 패턴 (PR 2cbd4e8 검증된 패턴 1:1).
     *
     * 페이지 경계 중복 방지 — 이전 페이지의 가장 오래된 row가 이미 수집됐으므로
     * 다음 페이지 cursor는 그 row의 직전 영업일이 시작. 단순화하여 1일 차감
     * (영업일 정확도 불필요 — distinctBy boundary dedup이 방어).
     *
     * 함정 #N (보수 페르소나 — 4 timeframe 공통 적용 검증):
     *   - 일봉(D): cursor = lastDate - 1day → KIS가 그 이전 일봉 100건 반환
     *   - 주봉(W): cursor = lastDate - 1day → KIS가 그 이전 주봉 100건 반환 (윈도우 자동 산정)
     *   - 월봉(M)/연봉(Y): 동일 — KIS endpoint는 fid_input_date_2 이전 데이터만 반환하므로
     *     1day 차감으로도 충분히 새 윈도우 확보. 더 큰 차감은 데이터 누락 위험.
     */
    private fun nextCursor(oldestDate: LocalDate): LocalDate = oldestDate.minusDays(1)

    /**
     * 종목코드 → KIS fid_cond_mrkt_div_code 결정.
     *
     * KIS raw spec (헌법 §3 의무 인용):
     *   FHKST03010230: fid_cond_mrkt_div_code = "J:주식, NX:NXT, UN:통합"
     *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     *   FHKST03010100: fid_cond_mrkt_div_code = "J:KRX, NX:NXT, UN:통합"
     *     raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * 분기 결정 (2026-05-20 ADR 0091):
     *   - KOSPI/KOSDAQ market만으로 UN을 선택하지 않는다. NXT 거래 가능 여부는 종목 단위로 변한다.
     *   - 현재 instrument에 별도 nxt_tradable 컬럼이 없으므로 is_kospi200/is_kosdaq150을 보수적 allow-list로 사용한다.
     *   - UN 후보도 응답 0건이면 J(KRX)로 fallback 한다. 캔들 적재 중단보다 KRX 기준 캔들 보존이 우선이다.
     *   - UNKNOWN/기타: "J" (안전 기본값)
     *
     * ADR 0063의 "market-level UN" 결정은 ADR 0091로 보강되었다.
     */
    private fun resolveMrktDivCode(code: String): String = resolveMrktDivCodeCandidates(code).first()

    private fun resolveMrktDivCodeCandidates(code: String): List<String> {
        val instrument = instrumentRepository.findById(code).orElse(null)
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

    override fun fetchCandles(
        code: String,
        interval: CandleInterval,
        count: Int,
    ): List<Candle> {
        val mrktDivCodes = resolveMrktDivCodeCandidates(code)
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val candles =
                if (interval == CandleInterval.ONE_MIN) {
                    fetchOneMinCandles(code, mrktDivCode)
                } else {
                    fetchDailyCandles(code, interval, mrktDivCode)
                }
            if (candles.isNotEmpty() || index == mrktDivCodes.lastIndex) {
                return candles
            }
            log.warn(
                "KIS candle UN 응답 0건 — J fallback 재시도: code={} interval={} mrktDivCode={}",
                code,
                interval.code,
                mrktDivCode,
            )
        }
        return emptyList()
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
     * Rate Limit (ADR 0037): 초당 10건. 50ms 마진 유지.
     * 11종목 × 4 call × 30일 = 1320 calls × 50ms = ~66s — 안전.
     *
     * in-memory dedup: (code, interval, ts epoch second) Set으로 중복 경계 row 방지.
     */
    private fun fetchOneMinCandles(
        code: String,
        mrktDivCode: String,
    ): List<Candle> {
        val cred =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?: run {
                    log.warn("KIS REST 1m candle: REAL credential 없음 — code={}", code)
                    return emptyList()
                }

        val userId = cred.id.userId
        // P0 hotfix 2026-05-20 — DB row 의 userId 빈 문자열 방어 (Redis `kis:token::REAL` cache pollution 차단)
        if (userId.isBlank()) {
            log.warn("KIS REST 1m candle: credential userId 빈 문자열 — code={} DB 결함 (cache pollution 차단)", code)
            return emptyList()
        }
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

            log.debug(
                "KIS 1m fetch market 분기: code={} market={} mrktDivCode={}",
                code,
                instrumentRepository
                    .findById(code)
                    .map {
                        it.market
                    }.orElse("UNKNOWN"),
                mrktDivCode,
            )

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
                    // D-1 try-finally drift 차단 패턴 (T-D-1): 페이지 단위 signalSent flag.
                    var pageSignalSent = false
                    var pageAcquired = false
                    try {
                        // Rate Limit acquire (spec PR #564 §2-2 LOCK — BACKFILL priority)
                        // ADR 0037 초당 10건 한도. 1m 페이지네이션 backfill path → BACKFILL 우선순위
                        kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)
                        pageAcquired = true

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

                        // KIS API rate limit 준수: ADR 0037 — 초당 10건 한도 (KisRateLimiterPort.acquire 합류 — spec PR #564 §2-2)
                        // sleep 마진 제거 — Bucket4j blocking polling이 적시 진입 보장
                        kisRateLimiterPort.onSuccess()
                        pageSignalSent = true
                    } catch (e: WebClientResponseException) {
                        val statusCode = e.statusCode.value()
                        if (statusCode == 429) {
                            // HTTP 429 → onTpsExceeded + 1초 backoff (spec PR #564 §2-2 LOCK)
                            kisRateLimiterPort.onTpsExceeded()
                            pageSignalSent = true
                            log.warn(
                                "KIS 1m candle HTTP 429 — 1초 backoff, 일자 건너뜀: code={} date={} hour={}",
                                code,
                                dateStr,
                                currentHour,
                            )
                            Thread.sleep(1000L)
                            dayDone = true
                        } else if (statusCode == 500) {
                            // KIS 500 INTERNAL_SERVER_ERROR — §33 exponential backoff 3회 재시도
                            // 배경: dogfooding 2026-05-11 PARTIAL_FAIL 패턴 (1회 retry 불충분)
                            // Rate Limit (ADR 0037): retry는 500 발생 시에만 — 초당 10건 한도 영향 미미
                            var retriedSuccessfully = false
                            for (attempt in 0 until RETRY_MAX_ATTEMPTS - 1) {
                                val waitMs = RETRY_BACKOFF_MS[attempt]
                                log.warn(
                                    "KIS 1m candle 500 오류 — {}ms backoff 후 재시도({}/{}): code={} date={} hour={}",
                                    waitMs,
                                    attempt + 2,
                                    RETRY_MAX_ATTEMPTS,
                                    code,
                                    dateStr,
                                    currentHour,
                                )
                                Thread.sleep(waitMs)
                                // D-1: 500 retry inner try drift 차단 (T-D-1).
                                var retrySignalSent = false
                                var retryAcquired = false
                                try {
                                    // Rate Limit acquire 재진입 (spec PR #564 §2-2 — retry path BACKFILL)
                                    kisRateLimiterPort.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)
                                    retryAcquired = true
                                    val retryBody =
                                        webClient
                                            .get()
                                            .uri { builder ->
                                                builder
                                                    .scheme("https")
                                                    .host(
                                                        kisBaseProd
                                                            .removePrefix("https://")
                                                            .substringBefore(":"),
                                                    ).port(
                                                        kisBaseProd
                                                            .substringAfterLast(":")
                                                            .toIntOrNull() ?: 9443,
                                                    ).path(ENDPOINT_1MIN)
                                                    .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                                    .queryParam("fid_input_iscd", code)
                                                    .queryParam("fid_input_hour_1", currentHour)
                                                    .queryParam("fid_input_date_1", dateStr)
                                                    .queryParam("fid_pw_data_incu_yn", "N")
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
                                    kisRateLimiterPort.onSuccess()
                                    retrySignalSent = true
                                    if (retryBody == null) {
                                        log.debug(
                                            "KIS 1m retry 빈 body: code={} date={} hour={} attempt={}/{}",
                                            code,
                                            dateStr,
                                            currentHour,
                                            attempt + 2,
                                            RETRY_MAX_ATTEMPTS,
                                        )
                                        dayDone = true
                                    } else {
                                        val parsed = parseKis1mResponse(retryBody, code, targetDate)
                                        if (parsed.isEmpty()) {
                                            dayDone = true
                                        } else {
                                            for (candle in parsed) {
                                                if (seenTs.add(candle.ts.epochSeconds)) allCandles.add(candle)
                                            }
                                            log.info(
                                                "KIS 1m candle 500 retry 성공: code={} date={} hour={} attempt={}/{} fetched={}",
                                                code,
                                                dateStr,
                                                currentHour,
                                                attempt + 2,
                                                RETRY_MAX_ATTEMPTS,
                                                parsed.size,
                                            )
                                            val earliestCandle =
                                                parsed.minByOrNull { it.ts.epochSeconds }
                                            if (earliestCandle != null) {
                                                val earliestLt =
                                                    java.time.Instant
                                                        .ofEpochSecond(earliestCandle.ts.epochSeconds)
                                                        .atZone(KST_ZONE)
                                                        .toLocalTime()
                                                if (!earliestLt.isAfter(LocalTime.of(8, 0, 0))) {
                                                    dayDone = true
                                                } else {
                                                    val nextLt =
                                                        earliestLt.minus(1, ChronoUnit.MINUTES)
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
                                    retriedSuccessfully = true
                                    break
                                } catch (retryEx: WebClientResponseException) {
                                    if (retryEx.statusCode.value() == 429) {
                                        kisRateLimiterPort.onTpsExceeded()
                                        retrySignalSent = true
                                    }
                                    if (retryEx.statusCode.value() == 500 &&
                                        attempt < RETRY_MAX_ATTEMPTS - 2
                                    ) {
                                        continue // 재시도 횟수 남음
                                    }
                                    log.warn(
                                        "KIS 1m candle 500 retry 소진({}/{}) — 일자 건너뜀: code={} date={} hour={} status={}",
                                        attempt + 2,
                                        RETRY_MAX_ATTEMPTS,
                                        code,
                                        dateStr,
                                        currentHour,
                                        retryEx.statusCode.value(),
                                    )
                                    dayDone = true
                                    retriedSuccessfully = true
                                    break
                                } catch (retryEx: Exception) {
                                    log.warn(
                                        "KIS 1m candle retry 오류: code={} date={} hour={} err={}",
                                        code,
                                        dateStr,
                                        currentHour,
                                        retryEx.message,
                                    )
                                    dayDone = true
                                    retriedSuccessfully = true
                                    break
                                } finally {
                                    if (retryAcquired && !retrySignalSent) {
                                        log.warn(
                                            "[KisRateLimit] 1m retry acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} date={}",
                                            code,
                                            dateStr,
                                        )
                                    }
                                }
                            }
                            if (!retriedSuccessfully) dayDone = true
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
                    } finally {
                        if (pageAcquired && !pageSignalSent) {
                            log.warn(
                                "[KisRateLimit] 1m page acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} date={}",
                                code,
                                dateStr,
                            )
                        }
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
     * 일봉·주봉·월봉·연봉 조회 (FHKST03010100) — **cursor 페이지네이션** (PR 2cbd4e8 검증된 패턴 1:1 재적용).
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * 페이지네이션 알고리즘 (PR 2cbd4e8 검증된 패턴 — 한국석유 004090 1년 backfill 243봉 GREEN):
     *   1. `cursor = today` 부터 역순 백워크 (외부 loop `for page in 0 until MAX_PAGES`)
     *   2. `fetchDailyCandlesSinglePage(code, interval, cursor)` — 1회 KIS 호출 (최대 100봉 응답)
     *   3. 응답 < KIS_PAGE_LIMIT(100) 이면 종료 (마지막 페이지 — 더 이상 과거 데이터 없음)
     *   4. 응답 0건 또는 cursor advance 실패 시 종료 (infinite loop guard)
     *   5. 다음 cursor = `oldestDate - 1day` → 다음 페이지
     *   6. distinctBy boundary dedup (페이지 경계 중복 ts 방어) + sortedBy ASC (PR #537 정규화 보존)
     *
     * regression 원인 evidence (PR #587 진단):
     *   PR 915699b (2026-05-11) Yahoo→KIS-only 재작성 시 페이지네이션 algorithm regression.
     *   결과: 30년 backfill 의도 → KIS 100건 한도로 잘림 (일봉 약 5개월만 수집).
     *
     * §33: HTTP 500 발생 시 exponential backoff 3회 재시도 (500ms → 1000ms → 2000ms) — single-page 내부.
     *
     * Rate Limit (ADR 0037 초당 10건): 6 calls × ~100ms = 600ms — 한도 영향 미미.
     *
     * ASC 정규화 LOCK (PR #537 보존):
     *   각 page 내부에서 parseKisDailyResponse가 이미 ASC 정규화 → 외부 sortedBy는 페이지 합산 후
     *   global ASC 보장. 이중 보강 — page 1 oldest + page 2 newest 사이 단조성 회귀 가드.
     *
     * 4 timeframe(D/W/M/Y) cover: fid_period_div_code 분기로 단일 함수가 4 PERIOD_DIV_CODE 처리
     *   (toPeriodDivCode 매핑). MAX_PAGES=6 × 100봉:
     *     - 일봉(D): 600봉 ≈ 약 2.5년 cover
     *     - 주봉(W): 600봉 ≈ 약 11년 cover
     *     - 월봉(M): 600봉 ≈ 약 50년 cover
     *     - 연봉(Y): 600봉 ≈ 600년 cover (사실상 무제한)
     */
    private fun fetchDailyCandles(
        code: String,
        interval: CandleInterval,
        mrktDivCode: String,
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
        // P0 hotfix 2026-05-20 — DB row 의 userId 빈 문자열 방어 (Redis `kis:token::REAL` cache pollution 차단)
        if (userId.isBlank()) {
            log.warn(
                "KIS REST candle: credential userId 빈 문자열 — code={} interval={} DB 결함 (cache pollution 차단)",
                code,
                interval.code,
            )
            return emptyList()
        }
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

            // market 분기 — KIS raw spec: J=KRX주식(KOSPI+KOSDAQ), UN=통합(KRX+NXT).
            // ADR 0091: market 단위 UN 금지, 종목 단위 후보만 UN 우선 + J fallback.
            // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
            val periodDivCode = toPeriodDivCode(interval)

            // PR 2cbd4e8 cursor 페이지네이션 (1:1 재적용 — 한국석유 검증된 패턴).
            // cursor = today부터 역순 walkback. MAX_PAGES=6 (≈ 2.5년 일봉 / 11년 주봉 / 50년 월봉).
            val accumulator = mutableListOf<Candle>()
            var cursor: LocalDate = LocalDate.now(KST_ZONE)
            // 30년 backfill 의도 — minimum 시작일 가드 (cursor가 이보다 더 과거로 가면 종료).
            val minStartDate: LocalDate = LocalDate.now(KST_ZONE).minusYears(30)

            for (page in 0 until MAX_PAGES) {
                if (cursor.isBefore(minStartDate)) {
                    log.debug(
                        "KIS REST candle 페이지네이션 minStartDate 도달 — 종료: code={} interval={} page={} cursor={}",
                        code,
                        interval.code,
                        page,
                        cursor,
                    )
                    break
                }

                val pageCandles =
                    fetchDailyCandlesSinglePage(
                        code = code,
                        interval = interval,
                        periodDivCode = periodDivCode,
                        mrktDivCode = mrktDivCode,
                        cursor = cursor,
                        minStartDate = minStartDate,
                        accessToken = accessToken,
                        appKeyStr = appKeyStr,
                        secretKeyStr = secretKeyStr,
                    )

                if (pageCandles.isEmpty()) {
                    log.debug(
                        "KIS REST candle 페이지 0건 — 종료: code={} interval={} page={} cursor={}",
                        code,
                        interval.code,
                        page,
                        cursor,
                    )
                    break
                }

                accumulator.addAll(pageCandles)

                // 마지막 페이지 — KIS_PAGE_LIMIT 미만 응답이면 더 이상 과거 데이터 없음 (PR 2cbd4e8 시나리오 6b).
                if (pageCandles.size < KIS_PAGE_LIMIT) {
                    log.debug(
                        "KIS REST candle 페이지 < {} — 마지막 페이지 종료: code={} interval={} page={} fetched={}",
                        KIS_PAGE_LIMIT,
                        code,
                        interval.code,
                        page,
                        pageCandles.size,
                    )
                    break
                }

                // 다음 cursor 산정 — 응답의 가장 오래된 ts - 1day.
                val oldestEpoch = pageCandles.minOf { it.ts.epochSeconds }
                val oldestDate =
                    java.time.Instant
                        .ofEpochSecond(oldestEpoch)
                        .atZone(KST_ZONE)
                        .toLocalDate()
                val newCursor = nextCursor(oldestDate)

                // infinite loop guard — cursor가 advance 안 하면 종료 (PR 2cbd4e8 시나리오 6 동일 가드).
                if (!newCursor.isBefore(cursor)) {
                    log.warn(
                        "KIS REST candle 페이지네이션 cursor advance 실패 — 종료: code={} interval={} page={} cursor={} newCursor={}",
                        code,
                        interval.code,
                        page,
                        cursor,
                        newCursor,
                    )
                    break
                }
                if (newCursor.isBefore(minStartDate)) {
                    log.debug(
                        "KIS REST candle 페이지네이션 newCursor < minStartDate — 종료: code={} interval={} page={} newCursor={}",
                        code,
                        interval.code,
                        page,
                        newCursor,
                    )
                    break
                }
                cursor = newCursor
            }

            // 페이지 경계 중복 방어 (distinctBy boundary dedup) + ASC 정규화 (PR #537 보존).
            // 각 page의 parseKisDailyResponse가 이미 ASC 정렬했으므로 외부 sortedBy는 페이지 합산 후 global ASC 보장.
            val result =
                accumulator
                    .distinctBy { it.ts.epochSeconds }
                    .sortedBy { it.ts.epochSeconds }

            log.info(
                "KIS REST candle 페이지네이션 완료: code={} interval={} pages_consumed={} total={} (raw_accumulated={})",
                code,
                interval.code,
                (accumulator.size + KIS_PAGE_LIMIT - 1) / KIS_PAGE_LIMIT,
                result.size,
                accumulator.size,
            )

            result
        } finally {
            appKey?.fill(0)
            secretKey?.fill(0)
        }
    }

    /**
     * 단일 페이지 KIS 호출 — 1회 호출 최대 KIS_PAGE_LIMIT(100) 봉 응답.
     *
     * PR 2cbd4e8 `fetchCandlesSinglePage` 패턴 1:1 — 외부 페이지네이션 loop와 분리하여 단일 책임.
     *
     * §33 retry 정책 적용 (HTTP 500 exponential backoff 3회):
     *   - 500 발생 시 RETRY_BACKOFF_MS[0..2] = 500ms → 1000ms → 2000ms 대기 후 재시도
     *   - 429 발생 시 onTpsExceeded + 1초 backoff 후 빈 결과
     *   - 기타 HTTP 오류 / 일반 예외 시 즉시 빈 결과 (외부 loop가 break)
     *
     * @param cursor fid_input_date_2 — 이 시각 이전 100건 반환
     * @param minStartDate fid_input_date_1 — 30년 전 (충분한 마진)
     */
    private fun fetchDailyCandlesSinglePage(
        code: String,
        interval: CandleInterval,
        periodDivCode: String,
        mrktDivCode: String,
        cursor: LocalDate,
        minStartDate: LocalDate,
        accessToken: String,
        appKeyStr: String,
        secretKeyStr: String,
    ): List<Candle> {
        val cursorStr = cursor.format(DATE_FMT)
        val startStr = minStartDate.format(DATE_FMT)

        // §33 exponential backoff retry — 500 발생 시 최대 3회
        // Rate Limit (ADR 0037): retry는 500 발생 시에만 — 초당 10건 한도 영향 미미
        var lastException: WebClientResponseException? = null
        for (attempt in 0 until RETRY_MAX_ATTEMPTS) {
            if (attempt > 0) {
                val waitMs = RETRY_BACKOFF_MS[attempt - 1]
                log.warn(
                    "KIS daily candle 500 오류 — {}ms backoff 후 재시도({}/{}): code={} interval={} cursor={}",
                    waitMs,
                    attempt + 1,
                    RETRY_MAX_ATTEMPTS,
                    code,
                    interval.code,
                    cursorStr,
                )
                Thread.sleep(waitMs)
            }
            // D-1 try-finally drift 차단 패턴 (T-D-1): attempt 단위 signalSent flag.
            var attemptSignalSent = false
            var attemptAcquired = false
            try {
                // Rate Limit acquire (spec PR #564 §2-2 LOCK — INGESTION priority)
                // ADR 0037 초당 10건 한도. 일봉/주봉/월봉/연봉 정기 갱신 path → INGESTION 우선순위
                kisRateLimiterPort.acquire(KisCallPriority.INGESTION, timeoutMillis = 1000L)
                attemptAcquired = true

                val body =
                    webClient
                        .get()
                        .uri { builder ->
                            builder
                                .scheme("https")
                                .host(kisBaseProd.removePrefix("https://").substringBefore(":"))
                                .port(kisBaseProd.substringAfterLast(":").toIntOrNull() ?: 9443)
                                .path(ENDPOINT_DAILY)
                                .queryParam("fid_cond_mrkt_div_code", mrktDivCode)
                                .queryParam("fid_input_iscd", code)
                                .queryParam("fid_input_date_1", startStr)
                                .queryParam("fid_input_date_2", cursorStr)
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
                        ?: run {
                            kisRateLimiterPort.onSuccess()
                            attemptSignalSent = true
                            return emptyList()
                        }
                if (attempt > 0) {
                    log.info(
                        "KIS daily candle 500 retry 성공: code={} interval={} cursor={} attempt={}/{}",
                        code,
                        interval.code,
                        cursorStr,
                        attempt + 1,
                        RETRY_MAX_ATTEMPTS,
                    )
                }
                kisRateLimiterPort.onSuccess()
                attemptSignalSent = true
                return parseKisDailyResponse(body, code, interval)
            } catch (e: WebClientResponseException) {
                if (e.statusCode.value() == 429) {
                    // HTTP 429 → onTpsExceeded + 1초 backoff (spec PR #564 §2-2 LOCK)
                    kisRateLimiterPort.onTpsExceeded()
                    attemptSignalSent = true
                    log.warn(
                        "KIS REST candle HTTP 429 — 1초 backoff 후 빈 결과: code={} interval={} cursor={}",
                        code,
                        interval.code,
                        cursorStr,
                    )
                    Thread.sleep(1000L)
                    return emptyList()
                }
                if (e.statusCode.value() == 500 && attempt < RETRY_MAX_ATTEMPTS - 1) {
                    lastException = e
                    continue // 재시도 횟수 남음 — backoff 후 재시도
                }
                log.warn(
                    "KIS REST candle 실패: code={} interval={} cursor={} status={}",
                    code,
                    interval.code,
                    cursorStr,
                    e.statusCode,
                )
                return emptyList()
            } catch (e: Exception) {
                log.warn(
                    "KIS REST candle 오류: code={} interval={} cursor={} err={}",
                    code,
                    interval.code,
                    cursorStr,
                    e.message,
                )
                return emptyList()
            } finally {
                if (attemptAcquired && !attemptSignalSent) {
                    log.warn(
                        "[KisRateLimit] daily candle attempt acquire 후 통계 시그널 미발행 — drift risk (caller 예외 추정): code={} cursor={}",
                        code,
                        cursorStr,
                    )
                }
            }
        }
        // retry 소진 (500 3회 모두 실패)
        log.warn(
            "KIS daily candle 500 retry 소진({}/{}) — 빈 결과 반환: code={} interval={} cursor={}",
            RETRY_MAX_ATTEMPTS,
            RETRY_MAX_ATTEMPTS,
            code,
            interval.code,
            cursorStr,
        )
        if (lastException != null) {
            log.debug("마지막 예외: {}", lastException.message)
        }
        return emptyList()
    }

    /**
     * FHKST03010230 1m 응답 파싱 — 1분봉 단일 timeframe.
     *
     * output2[]: stck_bsop_date (yyyyMMdd) + stck_cntg_hour (HHmmss) → KST timestamp
     * stck_oprc·stck_hgpr·stck_lwpr·stck_prpr(현재가=종가)·acml_vol
     *
     * KIS raw spec:
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     *
     * ⚠ KIS 응답 정렬 함정 (P0 fix, 2026-05-19 — chart-curator + ADR 0086 §11 정합):
     *   `inquire-time-dailychartprice`는 페이지네이션 커서(fid_input_hour_1) 역방향 백워크로
     *   페이지별 DESC 가능. parseKisDailyResponse와 동일한 햇징 룰 §1 monotonic risk → ASC 정규화 의무.
     *   본 함수 단일 LOCK으로 1m timeframe ASC 정규화 cover.
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
        // KIS DESC → ASC 정규화 (P0 fix 2026-05-19 chart-curator) — parseKisDailyResponse와 동일.
        return candles.sortedBy { it.ts.epochSeconds }
    }

    /**
     * FHKST03010100 일봉/주봉/월봉/연봉 응답 파싱 — **4 PERIOD_DIV_CODE 공통 처리**.
     *
     * 본 함수는 `inquire-daily-itemchartprice` (FHKST03010100)이 fid_period_div_code 분기로
     * 4 timeframe을 단일 endpoint·단일 응답 schema로 처리하는 KIS 공식 매트릭스 정합:
     *
     * | PERIOD_DIV_CODE | timeframe | CandleInterval | 호출 경로 |
     * |-----------------|-----------|----------------|----------|
     * | D | 일봉 | ONE_DAY | fetchDailyCandles → toPeriodDivCode |
     * | W | 주봉 | ONE_WEEK | fetchDailyCandles → toPeriodDivCode |
     * | M | 월봉 | ONE_MONTH | fetchDailyCandles → toPeriodDivCode |
     * | Y | 연봉 | ONE_YEAR | fetchDailyCandles → toPeriodDivCode |
     *
     * KIS raw spec — 4 timeframe 응답 schema 동일(output2[] · stck_bsop_date · OHLCV):
     *   https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
     *
     * ⚠ KIS 응답 정렬 함정 (P0 fix, 2026-05-19 — chart-curator + ADR 0086 §11 정합):
     *   KIS `inquire-daily-itemchartprice`는 응답 output2[]를 **최신→과거 DESC 순서**로 반환 (4 PERIOD_DIV_CODE 공통).
     *   햇징 룰 §1 (이중 검증) monotonic이 ASC 강제이므로,
     *   정규화 누락 시 idx 1+ 전부 reject → insertedCount=0 → 한국석유 evidence 5개월 갭 누적.
     *   따라서 본 파서가 `sortedBy { it.ts.epochSeconds }` ASC 정규화 책임 (chart-curator skill §5 source-of-truth + ADR 0086 §11 KIS raw 사전 정규화).
     *   KIS 공식 example도 `sort_values('stck_bsop_date', ascending=True)` 패턴 적용 (KIS-first 정합).
     *
     * ASC 정규화 적용 범위 — 본 함수 단일 LOCK으로 4 timeframe 전부 cover:
     *   - 일봉(D): 한국석유 PR #535·#537 evidence — 정규화 필수
     *   - 주봉(W): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
     *   - 월봉(M): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
     *   - 연봉(Y): 동일 endpoint·동일 schema·동일 DESC risk — 정규화 의무
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
        // KIS DESC → ASC 정규화 (P0 fix 2026-05-19 chart-curator).
        // 햇징 룰 §1 monotonic이 ASC 강제이므로 adapter layer가 source-of-truth로 정규화.
        val sorted = candles.sortedBy { it.ts.epochSeconds }
        log.debug("KIS REST candle 파싱: code={} interval={} count={} (ASC 정규화)", code, interval.code, sorted.size)
        return sorted
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

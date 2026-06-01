package kr.spdaewoon.magicjar.api.service

import kr.spdaewoon.magicjar.api.config.ChartCandleProperties
import kr.spdaewoon.magicjar.api.infrastructure.kis.KisTokenCacheHelper
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.InstrumentJpaRepository
import kr.spdaewoon.magicjar.api.persistence.UserKisCredentialJpaRepository
import kr.spdaewoon.magicjar.api.service.dto.CandleRecollectResponse
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshProgressUpdate
import kr.spdaewoon.magicjar.api.service.dto.CandleRefreshResponse
import kr.spdaewoon.magicjar.api.service.dto.IntervalRefreshResult
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleAggregator
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisErrorClass
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import kr.spdaewoon.magicjar.domain.market.ratelimit.classifyKisError
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
import kotlinx.datetime.Instant as KtInstant

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
 * 합산봉(3m·5m·15m·30m·90m·1h·4h·12h) 처리:
 *   KIS 미제공 — 요청 시 1m을 먼저 갱신한 뒤 서버에서 합산봉을 재생성한다.
 *
 * Rate Limit (ADR 0037 + 본 PR 종합 fix 2026-05-22 — KIS_TPS_EXCEEDED LIVE evidence 7 cycle ok=0 fail=6 매트릭스):
 *   - 초당 10건 KisRateLimiterPort.acquire 강제 (spec PR #564 §2-2 LOCK)
 *   - priority 분기: 사용자 트리거 = USER_PATH (즉시) / scheduler 종목 갱신 = BACKFILL (USER_PATH 양보)
 *   - H1 페이지 burst 완화: 페이지 간 [PAGE_INTERVAL_MS] sleep 마진 추가 (Bucket4j refillGreedy 100ms 보조,
 *     KIS server-side sliding window 가설 대비)
 *   - H4 EGW00201 catch 후 1초 backoff + 1회 재시도 (KisHashKeyAdapter:126 패턴 정합)
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
    private val credentialRepository: UserKisCredentialJpaRepository,
    private val kisRateLimiterPort: KisRateLimiterPort,
    private val chartCandleProperties: ChartCandleProperties,
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
         * H1 페이지 burst 완화 sleep 마진 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix).
         *
         * 결함 evidence (`_workspace/reports/2026-05-22-kis-tps-exceeded-root-cause.md` §8):
         *   - PR #658 (LiveDailyCandleScheduler 종목 간 200ms sleep) 머지 후 7 cycle 검증 ok=0 fail=6
         *     → H2(cooldown) 가설 reject. 실 root cause는 H1(refreshDailyCandles 50 페이지 burst)
         *   - Bucket4j refillGreedy 100ms당 1 token 회복 + capacity=10 idle 후 burst — KIS server-side가
         *     sub-second 분해 강제 시 첫 10 페이지가 ~1초 안에 모두 발사되어 EGW00201 차단
         *
         * 150ms 선정 사유:
         *   - Bucket4j 100ms refill의 50% 마진 (sub-second 분해 안전)
         *   - 50 페이지 × 150ms = 7.5초 추가 elapsed (5분 cron 윈도 대비 2.5% — 영향 미미)
         *   - KIS sliding window 1s 가설 대비 안전 마진
         *   - 첫 페이지(index 0)는 sleep 없음 — 사용자 USER_PATH 첫 응답 latency 보존
         */
        private const val PAGE_INTERVAL_MS = 150L

        /**
         * H4 EGW00201 catch 후 backoff 시간 (KisHashKeyAdapter:132 패턴 정합).
         *
         * 1초 = Bucket4j refill 1 cycle 완료 보장 (10 token 전체 회복).
         * KIS server-side window가 1초 단위라면 가장 안전한 reset point.
         */
        private const val RATE_LIMIT_BACKOFF_MS = 1000L

        // FHKST03010100 cursor 페이지네이션 최대 페이지 수 — **property 외부화** (ADR 0093 §3-1 LOCK).
        //
        // 기존 hardcode `DAILY_MAX_PAGES = 6` → [ChartCandleProperties.getMaxPagesFor] 동적 lookup.
        //
        // 매트릭스 (spec PR #609 §5 정합 — application.yml `magicjar.candle.max-pages-by-interval`):
        //   1d=50(5000봉·약 20년) / 1wk=20(2000봉·약 38년) / 1mo=12(1200봉·약 100년) / 1y=6(600봉)
        //
        // KIS `inquire-daily-itemchartprice` 응답 output2[]는 단일 호출 100건 limit (KIS API 정책).
        // 30년 범위(약 7560 거래일) 요청 시 단일 페이지로 전부 못 받음 → cursor 페이지네이션 의무.
        // Default fallback (config 부재 시): 6 — 기존 hardcode 회귀 안전.
        // B-1 consumer fix와 정합 — chart-curator PR #587 evidence + ADR 0086 §11 + ADR 0093.

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

        /** 1m 기준으로 서버에서 재생성하는 합산 interval. */
        private val AGGREGATE_INTERVALS =
            setOf(
                CandleInterval.THREE_MIN,
                CandleInterval.FIVE_MIN,
                CandleInterval.FIFTEEN_MIN,
                CandleInterval.THIRTY_MIN,
                CandleInterval.NINETY_MIN,
                CandleInterval.ONE_HOUR,
                CandleInterval.FOUR_HOUR,
                CandleInterval.TWELVE_HOUR,
            )

        private val AGGREGATE_INTERVAL_CODES = AGGREGATE_INTERVALS.map { it.code }.toSet()
        private val REFRESH_SUPPORTED_INTERVALS = SUPPORTED_INTERVALS + AGGREGATE_INTERVAL_CODES
        private val RECOLLECT_SUPPORTED_INTERVALS = SUPPORTED_INTERVALS + AGGREGATE_INTERVAL_CODES
    }

    /**
     * 종목 캔들 수동 갱신 진입점.
     *
     * @param code 종목코드 6자리
     * @param intervals 갱신 대상 interval 목록 (빈 리스트 = DEFAULT_INTERVALS 적용)
     * @param priority 호출 path 우선순위 (default USER_PATH — 사용자 트리거 path 보존).
     *                 LiveDailyCandleScheduler 등 백그라운드 cron은 BACKFILL 명시 의무
     *                 (2026-05-22 KIS_TPS_EXCEEDED LIVE 종합 fix — H1+H4 합류 §C).
     * @return CandleRefreshResponse (interval 별 결과 + 경과 시간)
     * @throws ResponseStatusException 404 (종목 없음) | 500 (KIS 인증 실패)
     */
    fun refresh(
        code: String,
        intervals: List<String>,
        priority: KisCallPriority = KisCallPriority.USER_PATH,
        progress: ((CandleRefreshProgressUpdate) -> Unit)? = null,
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
                intervals.filter { it in REFRESH_SUPPORTED_INTERVALS }.ifEmpty { DEFAULT_INTERVALS }
            }
        val aggregateTargets =
            effectiveIntervals
                .filter { it in AGGREGATE_INTERVAL_CODES }
                .distinct()
                .map { CandleInterval.fromCode(it) }
        val fetchIntervals =
            effectiveIntervals
                .fold(linkedSetOf<String>()) { acc, interval ->
                    if (interval in AGGREGATE_INTERVAL_CODES) {
                        acc.add(CandleInterval.ONE_MIN.code)
                    } else {
                        acc.add(interval)
                    }
                    acc
                }

        // 3. KIS 인증 (api 모듈 패턴 — KisRestPriceMetricsAdapter 정합)
        val (accessToken, appKey, secretKey) = resolveAccessTokenAndKeys()

        // 4. market division 후보 — ADR 0091: market 단위 KOSPI=UN 금지, 종목 단위 후보만 UN 우선 + J fallback.
        // raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_daily_itemchartprice/inquire_daily_itemchartprice.py
        val mrktDivCodes = resolveMrktDivCodeCandidates(code)

        // 5. interval 별 순차 갱신 (Rate Limit ADR 0037 초당 10건 + spec PR #564 §2-2 LOCK)
        // priority 전파: 사용자 path = USER_PATH (default) / scheduler path = BACKFILL (LiveDailyCandleScheduler)
        val results = mutableListOf<IntervalRefreshResult>()
        for (intervalCode in fetchIntervals) {
            progress?.invoke(
                CandleRefreshProgressUpdate(
                    stage = "FETCHING",
                    interval = intervalCode,
                    message = "$intervalCode 캔들 KIS 원천 수집 중",
                ),
            )
            val result =
                when (intervalCode) {
                    "1m" ->
                        refreshOneMinCandlesWithFallback(
                            code,
                            mrktDivCodes,
                            accessToken,
                            appKey,
                            secretKey,
                            priority,
                            progress,
                        )
                    in DAILY_INTERVALS ->
                        refreshDailyCandlesWithFallback(
                            code,
                            intervalCode,
                            mrktDivCodes,
                            accessToken,
                            appKey,
                            secretKey,
                            priority,
                            progress,
                        )
                    else -> IntervalRefreshResult(intervalCode, 0, 0, null, "SKIPPED")
                }
            results.add(result)
            progress?.invoke(
                CandleRefreshProgressUpdate(
                    stage = "FETCHED",
                    interval = intervalCode,
                    fetchedCount = result.fetchedCount,
                    insertedCount = result.insertedCount,
                    message = "$intervalCode 캔들 수집 완료 (${result.status})",
                ),
            )
        }
        if (aggregateTargets.isNotEmpty()) {
            results.addAll(rebuildAggregateIntervals(code, aggregateTargets, progress))
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
     * 캔들 재수집 진입점.
     *
     * 기존 refresh는 ON CONFLICT DO NOTHING upsert라 잘못 저장된 row를 교정하지 못한다.
     * recollect는 사용자가 명시 확인한 경우에만 기존 row를 삭제하고 KIS 원천을 다시 수집한다.
     *
     * 합산 interval(3m~12h)은 KIS가 직접 제공하지 않으므로 1m을 함께 재수집한 뒤 [CandleAggregator]로 재생성한다.
     */
    fun recollect(
        code: String,
        intervals: List<String>,
        priority: KisCallPriority = KisCallPriority.USER_PATH,
        progress: ((CandleRefreshProgressUpdate) -> Unit)? = null,
    ): CandleRecollectResponse {
        val startMs = System.currentTimeMillis()

        if (!instrumentJpaRepository.existsByCode(code)) {
            throw ResponseStatusException(HttpStatus.NOT_FOUND, "종목을 찾을 수 없습니다: $code")
        }

        val requested =
            intervals
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .distinct()
        if (requested.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "재수집할 interval을 지정해 주세요")
        }

        val unsupported = requested.filter { it !in RECOLLECT_SUPPORTED_INTERVALS }
        if (unsupported.isNotEmpty()) {
            throw ResponseStatusException(
                HttpStatus.BAD_REQUEST,
                "지원하지 않는 재수집 interval: ${unsupported.joinToString(",")} " +
                    "(허용: 1m·3m·5m·15m·30m·90m·1h·4h·12h·1d·1wk·1mo·1y)",
            )
        }

        // 삭제 전에 최소 인증/자격증명 문제를 먼저 확인해, 명백한 재수집 불가 상태에서 기존 데이터를 지우지 않는다.
        resolveAccessTokenAndKeys()

        val requestedSet = requested.toSet()
        val aggregateRequested = requestedSet.intersect(AGGREGATE_INTERVAL_CODES)
        val resetIntervalSet = linkedSetOf<String>()
        resetIntervalSet.addAll(requested)
        if (CandleInterval.ONE_MIN.code in requestedSet) {
            resetIntervalSet.addAll(AGGREGATE_INTERVAL_CODES)
        }
        if (aggregateRequested.isNotEmpty()) {
            resetIntervalSet.add(CandleInterval.ONE_MIN.code)
        }
        val resetIntervals = resetIntervalSet.toList()

        val refreshIntervalSet = linkedSetOf<String>()
        refreshIntervalSet.addAll(requested.filter { it in SUPPORTED_INTERVALS })
        if (aggregateRequested.isNotEmpty()) {
            refreshIntervalSet.add(CandleInterval.ONE_MIN.code)
        }
        val refreshIntervals = refreshIntervalSet.toList()

        progress?.invoke(
            CandleRefreshProgressUpdate(
                stage = "DELETING",
                interval = null,
                message = "기존 캔들 삭제 중",
            ),
        )
        val deletedCount = candleJpaRepository.deleteByCodeAndIntervalIn(code, resetIntervals)
        progress?.invoke(
            CandleRefreshProgressUpdate(
                stage = "DELETED",
                interval = null,
                insertedCount = 0,
                message = "기존 캔들 $deletedCount 건 삭제 완료",
            ),
        )
        val refreshResponse = refresh(code, refreshIntervals, priority, progress)

        val rebuildTargets =
            when {
                CandleInterval.ONE_MIN.code in requestedSet -> AGGREGATE_INTERVALS
                else -> aggregateRequested.map { CandleInterval.fromCode(it) }.toSet()
            }.sortedBy { it.code }
        val rebuiltIntervals = rebuildAggregateIntervals(code, rebuildTargets, progress)

        val elapsedMs = System.currentTimeMillis() - startMs
        log.info(
            "캔들 재수집 완료: code={} requested={} reset={} deleted={} rebuilt={} elapsedMs={}",
            code,
            requested,
            resetIntervals,
            deletedCount,
            rebuiltIntervals.map { it.interval },
            elapsedMs,
        )
        return CandleRecollectResponse(
            deletedCount = deletedCount,
            resetIntervals = resetIntervals,
            refreshedIntervals = refreshResponse.refreshedIntervals,
            rebuiltIntervals = rebuiltIntervals,
            elapsedMs = elapsedMs,
        )
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
        priority: KisCallPriority,
        progress: ((CandleRefreshProgressUpdate) -> Unit)?,
    ): IntervalRefreshResult {
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val result = refreshDailyCandles(code, intervalCode, mrktDivCode, accessToken, appKey, secretKey, priority)
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
        priority: KisCallPriority,
        progress: ((CandleRefreshProgressUpdate) -> Unit)?,
    ): IntervalRefreshResult {
        for ((index, mrktDivCode) in mrktDivCodes.withIndex()) {
            val result = refreshOneMinCandles(code, mrktDivCode, accessToken, appKey, secretKey, priority, progress)
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
     *   최대 페이지 수는 [ChartCandleProperties.getMaxPagesFor] 기반 timeframe별 매트릭스 (ADR 0093 §3-1).
     *   cursor advance 부재 시 break 무한루프 가드.
     *   ASC 정규화는 parseKisDailyOutput2 단일 LOCK (PR #558 정합) — 전체 페이지 합산 후 sortedBy.
     */
    private fun refreshDailyCandles(
        code: String,
        intervalCode: String,
        mrktDivCode: String,
        accessToken: String,
        appKey: String,
        secretKey: String,
        priority: KisCallPriority,
        retryAttempt: Int = 0,
    ): IntervalRefreshResult {
        val periodDivCode = DAILY_INTERVALS[intervalCode] ?: return IntervalRefreshResult(intervalCode, 0, 0, null, "SKIPPED")
        // 30년 전 기준 전수 수집 (KIS 응답 가능 범위 최대)
        val fromDate = LocalDate.now(KST_ZONE).minusYears(30)
        val startDateStr = fromDate.format(DATE_FMT)

        // ADR 0093 §3-1 LOCK — property 외부화 매트릭스 lookup (1d=50, 1wk=20, 1mo=12, 1y=6).
        // 기존 hardcode `DAILY_MAX_PAGES = 6` 폐기 — fallback 6 유지 (회귀 안전).
        val maxPages = chartCandleProperties.getMaxPagesFor(intervalCode)

        val allCandles = mutableListOf<KisDailyRow>()
        var cursor: LocalDate = LocalDate.now(KST_ZONE)
        var pageCount = 0
        var pageStatus = "OK"
        // H4 EGW00201 1회 재시도 게이트 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §B).
        // retryAttempt parameter LOCK — 메서드 재귀 호출마다 +1 누적, max=1 보장 (무한루프 차단).
        val canRetry = retryAttempt == 0

        // D-1 try-finally drift 차단 패턴 (T-D-1): 메서드 단위 outer signalSent 추적 — 각 페이지마다 갱신.
        // (refreshDailyCandles 메서드 outer try 전체에서 마지막 acquire의 시그널 미발행 path 감시)
        var lastPageSignalSent = true // 첫 페이지 진입 전 — outer catch에서 false 시그널 보강
        var lastPageAcquired = false
        try {
            while (pageCount < maxPages) {
                if (cursor < fromDate) break

                // H1 페이지 burst 완화 sleep 마진 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §A).
                //
                // 페이지 1(pageCount=0)은 sleep 없음 — 사용자 USER_PATH 첫 응답 latency 보존.
                // 페이지 2+ 부터 [PAGE_INTERVAL_MS]=150ms sleep 추가 — Bucket4j refillGreedy 100ms당 1 token
                // 회복 위에 50% 마진. KIS sliding window 가설 대비 sub-second 분해 안전 확보.
                //
                // 50 페이지 max × 150ms = 7.5초 추가 elapsed (5분 cron 윈도 2.5%) — 영향 미미.
                if (pageCount > 0) {
                    try {
                        Thread.sleep(PAGE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.warn(
                            "캔들 갱신 페이지 sleep interrupted — graceful stop: code={} interval={} page={}",
                            code,
                            intervalCode,
                            pageCount,
                        )
                        break
                    }
                }

                // Rate Limit acquire (spec PR #564 §2-2 LOCK — caller priority 전파)
                // ADR 0037 초당 10건 한도 강제. 페이지마다 acquire.
                // 사용자 트리거 = USER_PATH(즉시) / scheduler cron = BACKFILL(USER_PATH 양보 — H1 보완 §C)
                kisRateLimiterPort.acquire(priority, timeoutMillis = 3000L)
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

            if (pageCount >= maxPages) {
                log.info(
                    "캔들 갱신 MAX_PAGES 도달 안전 종료 (property 매트릭스 LOCK): code={} interval={} pages={} maxPages={}",
                    code,
                    intervalCode,
                    pageCount,
                    maxPages,
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
            // P0 hotfix (진단 PR #639) — EGW00201 = HTTP 500 wrap. classifyKisError() 분기 통일.
            if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                // Rate Limit (HTTP 429 또는 EGW00201) → onTpsExceeded 시그널.
                kisRateLimiterPort.onTpsExceeded()
                lastPageSignalSent = true

                // H4 1초 backoff + 1회 재시도 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §B).
                // KisHashKeyAdapter:126 패턴 정합 — RateLimit 분기에서 onTpsExceeded → sleep(1s) → retry.
                //
                // 재시도 진입 조건:
                //   - retryAttempt == 0 (첫 진입 frame)
                //   - 재귀 호출 시 retryAttempt=1 전달 → 두 번째 frame에서 또 RateLimit 발생해도 retry 안 함 (무한루프 차단)
                //
                // 재시도 후에도 RateLimit이면 RATE_LIMITED 종결 (mock 합성 0 — ADR 0014).
                if (canRetry) {
                    log.warn(
                        "캔들 갱신 Rate Limit — 1초 backoff 후 1회 재시도: code={} interval={} pages={} status={}",
                        code,
                        intervalCode,
                        pageCount,
                        e.statusCode.value(),
                    )
                    try {
                        Thread.sleep(RATE_LIMIT_BACKOFF_MS)
                    } catch (ie: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.warn(
                            "캔들 갱신 backoff sleep interrupted — RATE_LIMITED 종결: code={} interval={}",
                            code,
                            intervalCode,
                        )
                        return IntervalRefreshResult(intervalCode, allCandles.size, 0, null, "RATE_LIMITED")
                    }
                    // 재귀 재시도 — retryAttempt=1 전달. 두 번째 frame에서 또 RateLimit 발생 시 canRetry=false이므로 종결.
                    return refreshDailyCandles(
                        code,
                        intervalCode,
                        mrktDivCode,
                        accessToken,
                        appKey,
                        secretKey,
                        priority,
                        retryAttempt = 1,
                    )
                }

                log.warn(
                    "캔들 갱신 Rate Limit 재시도 후 재발 — RATE_LIMITED 종결: code={} interval={} pages={} status={}",
                    code,
                    intervalCode,
                    pageCount,
                    e.statusCode.value(),
                )
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
     * 1m 분봉 갱신 (FHKST03010230) — ADR 0105 one-minute 설정 기반 역방향 페이지네이션.
     *
     * raw: https://raw.githubusercontent.com/koreainvestment/open-trading-api/main/examples_llm/domestic_stock/inquire_time_dailychartprice/inquire_time_dailychartprice.py
     * - fid_cond_mrkt_div_code: J(KRX주식) 또는 UN(통합 — ADR 0091 종목 단위 후보만)
     * - fid_input_iscd: 종목코드
     * - fid_input_hour_1: 조회 기준 시각 (HHmmss) — 이 시각 이전 최신 120건 반환
     * - fid_input_date_1: 조회 날짜 (yyyyMMdd)
     * - fid_pw_data_incu_yn: N
     * - fid_fake_tick_incu_yn: (빈 문자열)
     *
     * Rate Limit (ADR 0037): [PAGE_INTERVAL_MS] 간격 유지.
     */
    private fun refreshOneMinCandles(
        code: String,
        mrktDivCode: String,
        accessToken: String,
        appKey: String,
        secretKey: String,
        priority: KisCallPriority,
        progress: ((CandleRefreshProgressUpdate) -> Unit)?,
    ): IntervalRefreshResult {
        val allRows = mutableListOf<Map<String, Any>>()
        val seenTs = mutableSetOf<Long>()

        val targetDays = oneMinuteTargetDays()
        val maxPagesPerDay = chartCandleProperties.oneMinute.normalizedMaxPagesPerDay()
        progress?.invoke(
            CandleRefreshProgressUpdate(
                stage = "FETCHING_1M",
                interval = "1m",
                processedDays = 0,
                totalDays = targetDays.size,
                fetchedCount = 0,
                message = "1m 캔들 ${targetDays.size}거래일 수집 시작",
            ),
        )

        // H4 1m path 1회 재시도 게이트 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §B).
        // 메서드 단위 LOCK — 30 거래일 × 8 페이지 max 전체에서 최대 1회 backoff + retry 보장.
        var oneMinRateLimitRetried = false

        for ((dayIndex, targetDate) in targetDays.withIndex()) {
            val dateStr = targetDate.format(DATE_FMT)
            var currentHour = "200000"
            var pageCount = 0
            var dayDone = false

            while (!dayDone && pageCount < maxPagesPerDay) {
                // H1 페이지 burst 완화 sleep 마진 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §A).
                // 페이지 1(pageCount=0)은 sleep 없음. 페이지 2+ 부터 [PAGE_INTERVAL_MS] sleep.
                if (pageCount > 0) {
                    try {
                        Thread.sleep(PAGE_INTERVAL_MS)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        log.warn(
                            "1m 갱신 페이지 sleep interrupted — graceful stop: code={} date={} page={}",
                            code,
                            dateStr,
                            pageCount,
                        )
                        dayDone = true
                        continue
                    }
                }

                // D-1 try-finally drift 차단 패턴 (T-D-1): 페이지 단위 signalSent flag.
                var pageSignalSent = false
                var pageAcquired = false
                try {
                    // Rate Limit acquire (spec PR #564 §2-2 LOCK — caller priority 전파)
                    // 사용자 트리거 = USER_PATH(즉시) / scheduler cron = BACKFILL(USER_PATH 양보)
                    kisRateLimiterPort.acquire(priority, timeoutMillis = 3000L)
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
                    // P0 hotfix (진단 PR #639) — classifyKisError() 분기 통일 (EGW00201 = HTTP 500 wrap).
                    if (classifyKisError(e.statusCode.value(), e.responseBodyAsString) is KisErrorClass.RateLimit) {
                        kisRateLimiterPort.onTpsExceeded()
                        pageSignalSent = true

                        // H4 1초 backoff + 1회 재시도 (2026-05-22 KIS_TPS_EXCEEDED 종합 fix §B).
                        // 메서드 단위 oneMinRateLimitRetried LOCK — 30 거래일 × 8 페이지 전체에서 1회만.
                        // 재시도는 currentHour 그대로 유지 + dayDone=false 유지 → while loop 다음 iteration이 같은 페이지 재진입.
                        if (!oneMinRateLimitRetried) {
                            oneMinRateLimitRetried = true
                            log.warn(
                                "1m 갱신 Rate Limit — 1초 backoff 후 1회 재시도: code={} date={} page={} status={}",
                                code,
                                dateStr,
                                pageCount,
                                e.statusCode.value(),
                            )
                            try {
                                Thread.sleep(RATE_LIMIT_BACKOFF_MS)
                            } catch (ie: InterruptedException) {
                                Thread.currentThread().interrupt()
                                log.warn("1m 갱신 backoff sleep interrupted — dayDone: code={} date={}", code, dateStr)
                                dayDone = true
                            }
                            // continue — 같은 currentHour로 while 다음 iteration 진입 (1회 재시도)
                            continue
                        }

                        log.warn(
                            "1m 갱신 Rate Limit 재시도 후 재발 — dayDone: code={} date={} page={} status={}",
                            code,
                            dateStr,
                            pageCount,
                            e.statusCode.value(),
                        )
                        dayDone = true
                        continue
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
            progress?.invoke(
                CandleRefreshProgressUpdate(
                    stage = "FETCHING_1M",
                    interval = "1m",
                    processedDays = dayIndex + 1,
                    totalDays = targetDays.size,
                    fetchedCount = allRows.size,
                    message = "1m 캔들 수집 중 (${dayIndex + 1}/${targetDays.size} 거래일)",
                ),
            )
        }

        // 수집된 raw rows → DB upsert
        val inserted = upsertOneMinRows(allRows, code)
        val latestTs = queryLatestTs(code, "1m")
        log.info("1m 캔들 갱신 완료: code={} fetched={} inserted={}", code, allRows.size, inserted)
        return IntervalRefreshResult("1m", allRows.size, inserted, latestTs, "OK")
    }

    private fun oneMinuteTargetDays(anchorDate: LocalDate = LocalDate.now(KST_ZONE)): List<LocalDate> {
        val lookbackTradingDays = chartCandleProperties.oneMinute.normalizedLookbackTradingDays()
        val maxCalendarScanDays = (lookbackTradingDays * 2).coerceAtLeast(lookbackTradingDays + 14)
        val targetDays = mutableListOf<LocalDate>()
        var date = anchorDate
        var checked = 0
        while (targetDays.size < lookbackTradingDays && checked < maxCalendarScanDays) {
            val dow = date.dayOfWeek
            if (dow != java.time.DayOfWeek.SATURDAY && dow != java.time.DayOfWeek.SUNDAY) {
                targetDays.add(date)
            }
            date = date.minusDays(1)
            checked++
        }
        return targetDays
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

    private fun rebuildAggregateIntervals(
        code: String,
        targets: Collection<CandleInterval>,
        progress: ((CandleRefreshProgressUpdate) -> Unit)? = null,
    ): List<IntervalRefreshResult> {
        if (targets.isEmpty()) return emptyList()

        val oneMinCandles =
            candleJpaRepository
                .findByCodeAndIntervalOrderByTsAsc(code, CandleInterval.ONE_MIN.code)
                .mapNotNull { it.toOneMinCandleOrNull() }

        if (oneMinCandles.isEmpty()) {
            return targets.map { target ->
                IntervalRefreshResult(target.code, 0, 0, null, "SKIPPED")
            }
        }

        return targets.mapIndexed { index, target ->
            runCatching {
                progress?.invoke(
                    CandleRefreshProgressUpdate(
                        stage = "REBUILDING",
                        interval = target.code,
                        processedDays = index,
                        totalDays = targets.size,
                        message = "${target.code} 합산봉 재생성 중",
                    ),
                )
                val aggregated = CandleAggregator.aggregate(oneMinCandles, target)
                val inserted = upsertAggregateCandles(aggregated)
                val result =
                    IntervalRefreshResult(
                        interval = target.code,
                        fetchedCount = aggregated.size,
                        insertedCount = inserted,
                        latestTs = queryLatestTs(code, target.code),
                        status = "OK",
                    )
                progress?.invoke(
                    CandleRefreshProgressUpdate(
                        stage = "REBUILT",
                        interval = target.code,
                        processedDays = index + 1,
                        totalDays = targets.size,
                        fetchedCount = aggregated.size,
                        insertedCount = inserted,
                        message = "${target.code} 합산봉 재생성 완료",
                    ),
                )
                result
            }.getOrElse { ex ->
                log.warn(
                    "합산봉 재생성 실패: code={} interval={} err={}",
                    code,
                    target.code,
                    ex.message,
                )
                IntervalRefreshResult(target.code, oneMinCandles.size, 0, null, "FAILED")
            }
        }
    }

    private fun upsertAggregateCandles(candles: List<Candle>): Int {
        var total = 0
        for (candle in candles) {
            total +=
                candleJpaRepository.insertIgnore(
                    code = candle.code,
                    interval = candle.interval.code,
                    ts = Instant.ofEpochSecond(candle.ts.epochSeconds),
                    openRaw = candle.openRaw,
                    highRaw = candle.highRaw,
                    lowRaw = candle.lowRaw,
                    closeRaw = candle.closeRaw,
                    volume = candle.volume,
                    source = candle.source,
                )
        }
        return total
    }

    private fun CandleJpaEntity.toOneMinCandleOrNull(): Candle? {
        val open = openRaw ?: return null
        val high = highRaw ?: return null
        val low = lowRaw ?: return null
        val close = closeRaw ?: return null
        return Candle(
            id = id,
            code = code,
            interval = CandleInterval.ONE_MIN,
            ts = KtInstant.fromEpochSeconds(ts.epochSecond),
            openRaw = open,
            highRaw = high,
            lowRaw = low,
            closeRaw = close,
            openAdj = openAdj,
            highAdj = highAdj,
            lowAdj = lowAdj,
            closeAdj = closeAdj,
            volume = volume ?: 0L,
            source = source,
        )
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
     * KIS-only 정보 수집 정책 (feedback_kis_key_realonly_for_data): REAL 자격증명 1순위 row 의 userId 사용.
     *
     * P0 hotfix 2026-05-20 (backend-core):
     *   - 기존: `tokenHelper.resolveTokenAndKeys(userId = "", ...)` → Redis `kis:token::REAL` cache pollution
     *     + cross-user leak (helper `loadCredential` 의 "DB 첫 row" fallback 결합) root cause
     *   - 신규: REAL credential 1순위 row 의 `id.userId` 명시적 추출 → helper 에 정확한 userId 전달.
     *     자격증명 부재 시 ResponseStatusException 502 변환 (UI 메시지 보존).
     */
    private fun resolveAccessTokenAndKeys(): Triple<String, String, String> {
        // 1순위 REAL credential — 부재 시 즉시 502 변환 (helper 호출 전 차단)
        val primaryUserId =
            credentialRepository
                .findAllByAccountType(AccountType.REAL.name)
                .firstOrNull()
                ?.id
                ?.userId
                ?: throw ResponseStatusException(
                    HttpStatus.INTERNAL_SERVER_ERROR,
                    "KIS 자격증명이 등록되지 않았습니다. 온보딩에서 KIS API 키를 등록해 주세요.",
                )

        return try {
            tokenHelper.resolveTokenAndKeys(userId = primaryUserId, accountType = AccountType.REAL)
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
}

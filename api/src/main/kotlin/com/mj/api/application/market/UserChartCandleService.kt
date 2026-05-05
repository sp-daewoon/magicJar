package com.mj.api.application.market

import com.mj.api.infrastructure.kis.KisChartCandleAdapter
import com.mj.api.messaging.producer.UserCandleEventPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.UserCandleCachePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 사용자 차트 화면용 candle fetch — Redis ZSet only. ADR 0050 §2-A.
 *
 * R3-5c (2026-05-05): 사용자 차트 path에서 DB candle 호출 0건. 사용자 정책 정합 완료.
 *   - D1·W·MO: KIS direct → 즉시 응답 + Kafka publish → consumer ZADD
 *   - M1: cache miss → yfinance backfill 트리거 (pyworker) + 빈 응답 → INSERT 시 STOMP fanout
 *   - Y/YEAR: D1 KIS direct + application 합성 (Redis 캐시 우회)
 *   - M<n>·H<n>·Xm: M1 base [fetch] → cache miss 시 trigger + 빈 응답
 *
 * 흐름 (M1 cache miss):
 *   사용자 → cache miss → trigger pyworker /backfill/yahoo → 빈 응답
 *           ↓ 백그라운드
 *           pyworker yfinance → Kafka publish (candle.minute.aggregated.v1)
 *           → CandleMinuteEventListener → DB INSERT (룰 엔진/백테스트용)
 *                                       + 종목 throttled publish (market.candle.user.v1)
 *           → consumer.UserCandleListener → Redis ZADD (사용자 차트용)
 *           → api.UserCandleStompFanoutListener → STOMP fanout
 *           → frontend invalidate → 자동 차트 갱신 (페이지 새로고침 불요)
 *
 * 핵심: 사용자 차트는 Redis only. DB candle은 룰 엔진/백테스트 전용 (CandleQueryService 등).
 */
@Service
class UserChartCandleService(
    private val cache: UserCandleCachePort,
    private val candleAggregator: CandleAggregator,
    private val kisChartCandleAdapter: KisChartCandleAdapter,
    private val userCandleEventPublisher: UserCandleEventPublisher,
    private val candleCacheService: CandleCacheService,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val zone: ZoneId = ZoneId.of("Asia/Seoul")

    /**
     * 사용자 차트 candle 조회.
     *
     * @param userId   사용자 식별자 (UUID). default = DEFAULT_USER_ID
     * @param code     KRX 종목코드
     * @param interval Interval enum (M1·D1만 R3-1 범위 — 합성 interval은 R3-2)
     * @param limit    최대 봉 수 (default = 200)
     * @param before   cursor — 이 시각 이전 봉만 (infinite scroll). null = 최신부터
     */
    fun fetch(
        userId: String,
        code: String,
        interval: Interval,
        limit: Int = DEFAULT_LIMIT,
        before: Instant? = null,
    ): List<Candle> {
        // R3-5c: 사용자 차트 path는 Redis only. DB candle 호출 0건 (ADR 0050 §2-A 정합).
        // M1만 cache miss 시 yfinance backfill 트리거. D1 등은 fetchKisDirect를 사용해야 함 (외부 호출자).
        val intervalKey = interval.name

        val cached = cache.fetch(userId, code, intervalKey, before, limit)
        // cache hit 조건: cached가 limit 이상 (이전의 작은 limit 호출 잔재가 큰 limit 호출에 전염되는 것 방지).
        if (cached.size >= limit) {
            val evicted = cache.touchRecent(userId, code)
            if (evicted.isNotEmpty()) {
                log.info("[USER_CANDLE] LRU evict user={} evicted={}", userId, evicted)
            }
            log.debug(
                "[USER_CANDLE] cache hit user={} code={} interval={} cached={} limit={}",
                userId,
                code,
                intervalKey,
                cached.size,
                limit,
            )
            return cached
        }

        // cache miss — Redis only path. DB 호출 0.
        log.info(
            "[USER_CANDLE] cache miss user={} code={} interval={} cached={} (M1: yfinance backfill 트리거)",
            userId,
            code,
            intervalKey,
            cached.size,
        )
        cache.touchRecent(userId, code)
        if (interval == Interval.M1) {
            // M1: pyworker /backfill/yahoo 비동기 트리거 → Kafka → consumer ZADD → STOMP fanout.
            // before가 지정된 경우(infinite scroll) 그 시점 이전 1000개분 fetch.
            candleCacheService.triggerBackgroundFetchAsync(code, before)
        }
        // cached가 일부 있으면 그대로 반환 (LRU+freshness용 partial), miss면 빈 배열.
        return cached
    }

    /**
     * 사용자 차트 candle 조회 — interval 문자열 기반. ADR 0050 R3-3 / R3-4 / R3-5a.
     *
     * 분기:
     *   - D1/DAY alias  → KIS direct (FID_PERIOD_DIV_CODE=D) [R3-5a]
     *   - M1/1m alias   → [fetch] (R3-3·R3-4 dual-write — R3-5b에서 yfinance direct로 전환)
     *   - W/WK          → KIS direct (FID_PERIOD_DIV_CODE=W) [R3-4]
     *   - MO/MONTH      → KIS direct (FID_PERIOD_DIV_CODE=M) [R3-4]
     *   - Y/YEAR        → D1 base [fetch] + [CandleAggregator.aggregateDailyToWeekly] (합성 유지 — internal D1 cache 사용)
     *   - M<n>/<n>m/H<n>/<n>h → M1 base [fetch] + [CandleAggregator.aggregate]
     *
     * KIS direct (D1·W·MO) 결과는 publisher.publish → consumer ZADD로 Redis 적재 + STOMP fanout.
     * Y/분봉 합성용 internal base 호출은 [fetch]를 거쳐 DB fallback 사용 (R3-5b에서 정리).
     */
    fun fetchByString(
        userId: String,
        code: String,
        intervalRaw: String,
        limit: Int = DEFAULT_LIMIT,
        before: Instant? = null,
    ): List<Candle> {
        val upper = intervalRaw.trim().uppercase()

        // D1/DAY — KIS direct fetch (ADR 0050 R3-5a)
        if (upper in D1_ALIASES) return fetchKisDirect(userId, code, Interval.D1, limit, before)

        if (upper in M1_ALIASES) return fetch(userId, code, Interval.M1, limit, before)

        // W/WK — KIS direct fetch (ADR 0050 R3-4)
        if (upper == "W" || upper == "WK") {
            return fetchKisDirect(userId, code, Interval.WEEK, limit, before)
        }

        // MO/MONTH — KIS direct fetch (ADR 0050 R3-4)
        if (upper == "MO" || upper == "MONTH") {
            return fetchKisDirect(userId, code, Interval.MONTH, limit, before)
        }

        // Y/YEAR — D1 base + application 합성 (ADR 0050 §2-D: 1y는 합성 유지).
        // R3-5c: D1 base를 fetchKisDirect로 직접 가져옴 (DB 우회). KIS adapter 페이지 한도 내 가능한 봉 수만큼 반환.
        if (upper == "Y" || upper == "YEAR") {
            val baseLimit = (limit.toLong() * 250).coerceAtMost(MAX_BASE_LIMIT.toLong()).toInt()
            val d1Candles = fetchKisDirect(userId, code, Interval.D1, baseLimit, before)
            val aggregated = candleAggregator.aggregateDailyToWeekly(d1Candles, upper)
            return if (aggregated.size > limit) aggregated.takeLast(limit) else aggregated
        }

        // M<n>/Xm/Hn — M1 base + aggregate
        val targetMinutes =
            CandleAggregator.parseToMinutesOrNull(intervalRaw)
                ?: throw IllegalArgumentException("Unsupported interval: $intervalRaw")
        require(targetMinutes > 0) { "interval must be positive: $intervalRaw" }
        require(targetMinutes <= 1440) { "interval must be <= 1440 min: $intervalRaw" }

        val m1Limit = (limit.toLong() * targetMinutes).coerceAtMost(MAX_BASE_LIMIT.toLong()).toInt()
        val m1Candles = fetch(userId, code, Interval.M1, m1Limit, before)
        val aggregated = candleAggregator.aggregate(m1Candles, targetMinutes)
        return if (aggregated.size > limit) aggregated.takeLast(limit) else aggregated
    }

    /**
     * KIS direct fetch (D1/W/MO) — Redis 캐시 우회. ADR 0050 R3-4 + R3-5a.
     *
     * KIS 키 미설정 시 빈 리스트 반환 (graceful degradation).
     * 결과는 [UserCandleEventPublisher.publish]로 Kafka 발행 →
     *   - consumer.UserCandleListener: Redis ZADD (single writer)
     *   - api.UserCandleStompFanoutListener: STOMP /topic/market.candle.user.{userId}.{code} fanout
     *
     * @param userId   사용자 식별자 (Kafka payload·STOMP key용)
     * @param code     KRX 종목코드
     * @param interval [Interval.D1] · [Interval.WEEK] · [Interval.MONTH]
     * @param limit    최대 봉 수 (ADR 0050 §2-D: D1=500, W=200, MO=60)
     * @param before   cursor — 이 시각 이전 봉만 (무한 스크롤). null = 최신부터
     */
    private fun fetchKisDirect(
        userId: String,
        code: String,
        interval: Interval,
        limit: Int,
        before: Instant?,
    ): List<Candle> {
        val to = (before?.let { LocalDate.ofInstant(it, zone) } ?: LocalDate.now(zone))
        val from =
            when (interval) {
                Interval.D1 -> to.minusDays((limit * 1.7).toLong() + 7L)
                Interval.WEEK -> to.minusWeeks(limit.toLong() + 10L)
                Interval.MONTH -> to.minusMonths(limit.toLong() + 3L)
                else -> to.minusDays(limit.toLong() * 2)
            }

        log.debug(
            "[USER_CANDLE] KIS direct fetch user={} code={} interval={} from={} to={} limit={}",
            userId,
            code,
            interval,
            from,
            to,
            limit,
        )
        val candles = kisChartCandleAdapter.fetchCandles(code, interval, from, to)
        val trimmed = if (candles.size > limit) candles.takeLast(limit) else candles

        // R3-5a: LRU touchRecent (사용자 진입 의사 표현)
        val evicted = cache.touchRecent(userId, code)
        if (evicted.isNotEmpty()) {
            log.info("[USER_CANDLE] LRU evict user={} evicted={}", userId, evicted)
        }

        // R3-5a: Kafka publish — consumer ZADD + STOMP fanout (fail-soft)
        if (trimmed.isNotEmpty()) {
            userCandleEventPublisher.publish(userId, code, interval.name, trimmed)
        }

        return trimmed
    }

    /**
     * before 없으면 최신 limit개의 lookback으로 from·to 계산.
     * - D1: limit일 * 1.7 (주말·공휴일 buffer — 5/7 영업일 + 공휴일)
     * - M1: limit분 (장중 5h*60=300분 단위로 큰 limit는 일 단위 환산)
     */
    private fun computeRange(
        interval: Interval,
        before: Instant?,
        limit: Int,
    ): Pair<Instant, Instant> {
        val to = before ?: Instant.now()
        val daysBack: Long =
            when (interval) {
                // D1: limit개 영업일 + 주말·공휴일 buffer (× 1.7 + 7일 여유)
                Interval.D1 -> ((limit * 1.7).toLong() + 7L).coerceAtLeast(7L)
                // M1: 장 5시간(300분) per day → limit개 잡으려면 limit/300일치, 최소 7일.
                //     M1 yfinance 한도 30일 — 그 이상 가더라도 DB는 30일치만 반환.
                Interval.M1 -> ((limit / 300L) + 7L).coerceAtLeast(7L)
                // WEEK/MONTH는 fetchKisDirect에서 처리 — 여기서는 호출되지 않음.
                else -> ((limit * 1.7).toLong() + 7L).coerceAtLeast(7L)
            }
        val from = to.minus(daysBack, ChronoUnit.DAYS)
        return from to to
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 200

        /** 합성 시 base interval(M1·D1) fetch 한도. CandleQueryService의 10_000과 동일. */
        const val MAX_BASE_LIMIT: Int = 10_000

        private val D1_ALIASES = setOf("D1", "DAY", "D", "1D")
        private val M1_ALIASES = setOf("M1", "1M", "1MIN", "MIN1")

        /** ADR 0049 정합 — 단일 사용자 모드 default. KIS 모의 계좌번호 8자리. */
        const val DEFAULT_USER_ID: String = "74683223"
    }
}

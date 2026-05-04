package com.mj.api.application.market

import com.mj.api.infrastructure.kis.KisChartCandleAdapter
import com.mj.api.messaging.producer.UserCandleEventPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.UserCandleCachePort
import com.mj.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * 사용자 차트 화면용 candle fetch — Redis ZSet first. ADR 0050.
 *
 * R3-5a (2026-05-05): 사용자 직접 D1 요청은 KIS direct + Kafka publish (DB 안 거침).
 * api 측 직접 ZADD 제거 — consumer가 단일 ZADD writer (ADR 0050 §2-A 정합).
 * Y/분봉 합성용 internal D1 base는 [fetch]가 DB fallback path 그대로 (R3-5b에서 정리).
 * M1 base는 R3-5b에서 yfinance direct로 전환 예정.
 *
 * 흐름 (D1 사용자 직접):
 *   사용자 → KisChartCandleAdapter (KIS REST, FID_PERIOD_DIV_CODE=D)
 *         → publisher.publish(market.candle.user.v1)
 *         → 응답 (KIS direct 결과 그대로)
 *               ↓ Kafka
 *               consumer.UserCandleListener → Redis ZADD
 *               api.UserCandleStompFanoutListener → STOMP /topic/market.candle.user.{userId}.{code}
 *
 * 흐름 (D1 internal base — Y/분봉 합성용):
 *   [fetch] → cache hit / DB fallback → Redis warm-up + publish (legacy R3-3·R3-4 path)
 *   ※ R3-5b에서 외부 API direct로 통합 예정.
 *
 * 흐름 (M1):
 *   [fetch] → cache hit / DB fallback → Redis warm-up + publish (legacy)
 *   ※ R3-5b에서 yfinance direct로 전환 예정.
 *
 * 핵심: DB candle 영속은 그대로 유지 (백테스트·룰 엔진용). ADR 0050 §2-A 분리.
 */
@Service
class UserChartCandleService(
    private val cache: UserCandleCachePort,
    private val candleRepository: CandleRepository,
    private val candleAggregator: CandleAggregator,
    private val kisChartCandleAdapter: KisChartCandleAdapter,
    private val userCandleEventPublisher: UserCandleEventPublisher,
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
        // R3-1: cache hit 시 ZSet 그대로 반환. miss 시 DB 조회 + warm-up.
        val intervalKey = interval.name

        val cached = cache.fetch(userId, code, intervalKey, before, limit)
        // cache hit 조건: cached가 limit 이상 (이전의 작은 limit 호출 잔재가 큰 limit 호출에 전염되는 것 방지).
        // limit 미만이면 무조건 DB fallback → warm-up.
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

        // cache miss — DB 조회 후 warm-up
        log.debug(
            "[USER_CANDLE] cache miss user={} code={} interval={} cached={} → DB fallback",
            userId,
            code,
            intervalKey,
            cached.size,
        )
        val (from, to) = computeRange(interval, before, limit)
        val dbCandles = candleRepository.find(code, interval, from, to)

        if (dbCandles.isEmpty()) {
            log.debug("[USER_CANDLE] DB miss user={} code={} interval={} — empty response", userId, code, intervalKey)
            // touchRecent는 진입 의사 표현이라 실행 (LRU 갱신)
            cache.touchRecent(userId, code)
            return emptyList()
        }

        // Redis ZADD warm-up
        cache.upsertAll(userId, code, intervalKey, dbCandles)
        cache.setLastFetch(userId, code, intervalKey, Instant.now())
        val evicted = cache.touchRecent(userId, code)
        if (evicted.isNotEmpty()) {
            log.info("[USER_CANDLE] LRU evict user={} evicted={}", userId, evicted)
        }
        log.info(
            "[USER_CANDLE] DB → Redis warm-up user={} code={} interval={} n={}",
            userId,
            code,
            intervalKey,
            dbCandles.size,
        )

        // R3-4 dual-write: Kafka publish (fail-soft — 발행 실패가 사용자 응답 차단 안 함)
        userCandleEventPublisher.publish(userId, code, intervalKey, dbCandles)

        // 응답 — limit·before 적용
        return cache.fetch(userId, code, intervalKey, before, limit)
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

        // Y/YEAR — D1 base + application 합성 (ADR 0050 §2-D: 1y는 합성 유지)
        if (upper == "Y" || upper == "YEAR") {
            val baseLimit = (limit.toLong() * 250).coerceAtMost(MAX_BASE_LIMIT.toLong()).toInt()
            val d1Candles = fetch(userId, code, Interval.D1, baseLimit, before)
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

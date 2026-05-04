package com.mj.api.application.market

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.UserCandleCachePort
import com.mj.domain.market.port.CandleRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.UUID

/**
 * 사용자 차트 화면용 candle fetch — Redis ZSet first + DB fallback. ADR 0050.
 *
 * 동작:
 *   1. cache hit (Redis ZSet에 limit개 이상 있으면) → 즉시 응답 + LRU touchRecent
 *   2. cache miss → DB candle 조회 → Redis ZADD warm-up + LRU touchRecent + 응답
 *   3. DB 비어 있으면 → 빈 응답 (backfill trigger는 R3-2에서 통합)
 *
 * 핵심: DB candle 영속은 그대로 유지 (백테스트·룰 엔진용). 차트 화면은 Redis 통과.
 *
 * R3-1 범위 — Redis warm-up + cache hit 흐름. backfill 명령 통합·봉단위 합성·자유 입력은 R3-2.
 */
@Service
class UserChartCandleService(
    private val cache: UserCandleCachePort,
    private val candleRepository: CandleRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
        userId: UUID,
        code: String,
        interval: Interval,
        limit: Int = DEFAULT_LIMIT,
        before: Instant? = null,
    ): List<Candle> {
        // R3-1: cache hit 시 ZSet 그대로 반환. miss 시 DB 조회 + warm-up.
        val intervalKey = interval.name

        val cached = cache.fetch(userId, code, intervalKey, before, limit)
        if (cached.size >= limit || (before == null && cached.isNotEmpty())) {
            // cache hit — touchRecent로 LRU 갱신 + 즉시 응답
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

        // 응답 — limit·before 적용
        return cache.fetch(userId, code, intervalKey, before, limit)
    }

    /** before 없으면 최신 limit개의 lookback으로 from·to 계산. */
    private fun computeRange(
        interval: Interval,
        before: Instant?,
        limit: Int,
    ): Pair<Instant, Instant> {
        val to = before ?: Instant.now()
        val secondsPerCandle = if (interval == Interval.D1) 86400L else 60L
        // 영업일 considering 없이 단순 backfill — backend candle 조회는 from~to 사이 모든 행 반환
        val from = to.minus(secondsPerCandle * limit, ChronoUnit.SECONDS)
        return from to to
    }

    companion object {
        const val DEFAULT_LIMIT: Int = 200

        /** ADR 0049 정합 — 단일 사용자 모드 default. */
        val DEFAULT_USER_ID: UUID = UUID.fromString("00000000-0000-0000-0000-000000000001")
    }
}

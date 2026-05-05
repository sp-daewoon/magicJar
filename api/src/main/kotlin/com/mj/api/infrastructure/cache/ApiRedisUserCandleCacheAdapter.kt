package com.mj.api.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.cache.port.CacheRangeMeta
import com.mj.domain.market.cache.port.UserCandleCachePort
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant

/**
 * Redis ZSet 기반 사용자 차트 캐시 구현체. ADR 0050.
 *
 * 키 구조:
 *   candle:{userId}:{code}:{interval}      ZSet (score=epoch second, value=JSON OHLCV)
 *   candle:recent:{userId}                 ZSet (score=last access ms, value=code) LIMIT 10
 *   candle:lastfetch:{userId}:{code}:{interval} String (epoch second)
 *
 * TTL: 없음 — LRU 10 정책으로만 cleanup.
 *
 * profile 무관 — Redis 빈은 항상 등록되어 있고, mock에서도 LettuceConnectionFactory가 in-memory
 * Redis로 대체될 수 있으나 현재는 docker-compose의 redis 컨테이너 사용.
 */
@Component
class ApiRedisUserCandleCacheAdapter(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) : UserCandleCachePort {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 캔들 JSON 표현 (ZSet value). 도메인 Candle을 직렬화 가능한 단순 형태로 변환. */
    data class CandleJson(
        val o: String,
        val h: String,
        val l: String,
        val c: String,
        val v: Long,
        val tc: Long = 0L,
        val itv: String, // interval — 역직렬화 시 필요
    ) {
        companion object {
            fun from(candle: Candle): CandleJson =
                CandleJson(
                    o = candle.open.toPlainString(),
                    h = candle.high.toPlainString(),
                    l = candle.low.toPlainString(),
                    c = candle.close.toPlainString(),
                    v = candle.volume,
                    tc = candle.tradeCount,
                    itv = candle.interval.name,
                )
        }

        fun toCandle(
            code: String,
            openTime: Instant,
        ): Candle =
            Candle(
                code = code,
                interval = Interval.valueOf(itv),
                openTime = openTime,
                open = BigDecimal(o),
                high = BigDecimal(h),
                low = BigDecimal(l),
                close = BigDecimal(c),
                volume = v,
                tradeCount = tc,
            )
    }

    override fun upsert(
        userId: String,
        code: String,
        interval: String,
        candle: Candle,
    ) {
        val key = candleKey(userId, code, interval)
        val score = candle.openTime.epochSecond.toDouble()
        val value = mapper.writeValueAsString(CandleJson.from(candle))
        // 같은 score(openTime)은 덮어쓰기 — Redis ZADD는 같은 value면 score만 갱신.
        // openTime 동일·OHLCV 다른 경우 value 다르므로 별도 entry. 정합 위해 기존 entry 삭제 후 ADD.
        redis.opsForZSet().removeRangeByScore(key, score, score)
        redis.opsForZSet().add(key, value, score)
    }

    override fun upsertAll(
        userId: String,
        code: String,
        interval: String,
        candles: List<Candle>,
    ) {
        if (candles.isEmpty()) return
        val key = candleKey(userId, code, interval)
        // 같은 score range 삭제 후 일괄 ADD — 정합·idempotency 보장
        candles.forEach { c ->
            val score = c.openTime.epochSecond.toDouble()
            redis.opsForZSet().removeRangeByScore(key, score, score)
            val value = mapper.writeValueAsString(CandleJson.from(c))
            redis.opsForZSet().add(key, value, score)
        }
        log.debug("[USER_CANDLE_CACHE] upsertAll user={} code={} interval={} n={}", userId, code, interval, candles.size)
    }

    override fun fetch(
        userId: String,
        code: String,
        interval: String,
        before: Instant?,
        limit: Int,
    ): List<Candle> {
        val key = candleKey(userId, code, interval)
        // before 지정 시 score < before 범위에서 limit개. 미지정 시 최신부터 limit개.
        val rawSet: Set<String>? =
            if (before == null) {
                // 최신 limit개 — ZREVRANGE 0 (limit-1) WITHSCORES → 최신순. 그 후 reverse.
                redis.opsForZSet().reverseRange(key, 0L, (limit - 1).toLong())
            } else {
                val maxScore = before.epochSecond.toDouble() - 1.0
                redis
                    .opsForZSet()
                    .reverseRangeByScore(
                        key,
                        Double.NEGATIVE_INFINITY,
                        maxScore,
                        0L,
                        limit.toLong(),
                    )
            }
        val raw = rawSet ?: return emptyList()
        // 각 raw value에 대응하는 score(openTime) 조회 — opsForZSet().score()
        return raw
            .mapNotNull { jsonValue ->
                val score =
                    redis.opsForZSet().score(key, jsonValue) ?: return@mapNotNull null
                val openTime = Instant.ofEpochSecond(score.toLong())
                runCatching { mapper.readValue<CandleJson>(jsonValue).toCandle(code, openTime) }
                    .getOrNull()
            }.sortedBy { it.openTime } // 항상 ASC 반환
    }

    override fun touchRecent(
        userId: String,
        code: String,
    ): List<String> {
        val recentKey = recentKey(userId)
        val now = System.currentTimeMillis().toDouble()
        // ZADD — 이미 있으면 score만 갱신 (LRU)
        redis.opsForZSet().add(recentKey, code, now)

        // LRU 10 한도 초과 시 가장 오래된 (score 작은 것) 종목들 evict
        val size = redis.opsForZSet().size(recentKey) ?: 0L
        if (size <= LRU_LIMIT) return emptyList()

        val excessCount = (size - LRU_LIMIT).toInt()
        val toEvictSet: Set<String>? =
            redis.opsForZSet().range(recentKey, 0L, (excessCount - 1).toLong())
        val toEvict = toEvictSet?.toList() ?: return emptyList()

        toEvict.forEach { evictedCode ->
            // 해당 사용자의 모든 interval candle 키 삭제
            val pattern = "$CANDLE_PREFIX$userId:$evictedCode:*"
            val keys = redis.keys(pattern)
            if (!keys.isNullOrEmpty()) redis.delete(keys)
            // lastfetch 키도 삭제
            val lastfetchPattern = "$LASTFETCH_PREFIX$userId:$evictedCode:*"
            val lastfetchKeys = redis.keys(lastfetchPattern)
            if (!lastfetchKeys.isNullOrEmpty()) redis.delete(lastfetchKeys)
            // recent ZSet에서 제거
            redis.opsForZSet().remove(recentKey, evictedCode)
            log.info("[USER_CANDLE_CACHE] LRU evict user={} code={}", userId, evictedCode)
        }
        return toEvict
    }

    override fun setLastFetch(
        userId: String,
        code: String,
        interval: String,
        at: Instant,
    ) {
        redis.opsForValue().set(lastfetchKey(userId, code, interval), at.epochSecond.toString())
    }

    override fun getLastFetch(
        userId: String,
        code: String,
        interval: String,
    ): Instant? {
        val raw = redis.opsForValue().get(lastfetchKey(userId, code, interval)) ?: return null
        return raw.toLongOrNull()?.let { Instant.ofEpochSecond(it) }
    }

    override fun rangeMeta(
        userId: String,
        code: String,
        interval: String,
    ): CacheRangeMeta {
        val key = candleKey(userId, code, interval)
        val count = redis.opsForZSet().size(key) ?: 0L
        if (count == 0L) return CacheRangeMeta.EMPTY

        val firstScore =
            redis
                .opsForZSet()
                .rangeWithScores(key, 0L, 0L)
                ?.firstOrNull()
                ?.score
        val lastScore =
            redis
                .opsForZSet()
                .reverseRangeWithScores(key, 0L, 0L)
                ?.firstOrNull()
                ?.score
        return CacheRangeMeta(
            first = firstScore?.toLong()?.let(Instant::ofEpochSecond),
            last = lastScore?.toLong()?.let(Instant::ofEpochSecond),
            count = count,
        )
    }

    private fun candleKey(
        userId: String,
        code: String,
        interval: String,
    ) = "$CANDLE_PREFIX$userId:$code:$interval"

    private fun recentKey(userId: String) = "$RECENT_PREFIX$userId"

    private fun lastfetchKey(
        userId: String,
        code: String,
        interval: String,
    ) = "$LASTFETCH_PREFIX$userId:$code:$interval"

    companion object {
        const val CANDLE_PREFIX: String = "candle:"
        const val RECENT_PREFIX: String = "candle:recent:"
        const val LASTFETCH_PREFIX: String = "candle:lastfetch:"

        /** LRU 10 종목 한도 (ADR 0050 §2-C). */
        const val LRU_LIMIT: Long = 10L
    }
}

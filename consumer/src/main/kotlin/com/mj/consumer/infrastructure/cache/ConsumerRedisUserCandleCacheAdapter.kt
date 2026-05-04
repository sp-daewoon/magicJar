package com.mj.consumer.infrastructure.cache

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.domain.market.event.CandleSnapshot
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component

/**
 * consumer 모듈 전용 Redis ZADD 어댑터 — ADR 0050 R3-4.
 *
 * api의 [com.mj.api.infrastructure.cache.ApiRedisUserCandleCacheAdapter]와 동일한
 * 키 구조·직렬화 포맷을 사용하므로 두 어댑터가 동일 Redis ZSet에 쓰고 api가 읽는다.
 *
 * 키 구조 (api 어댑터와 공유):
 *   candle:{userId}:{code}:{interval}      ZSet (score=epoch second, value=JSON OHLCV)
 *
 * dual-write 특성상 api 직접 ZADD와 consumer ZADD가 같은 key에 동시에 쓸 수 있다.
 * ZADD score 동일 → api가 이미 쓴 entry를 consumer가 덮어쓰거나 역순이 될 수 있으나
 * openTime(score)이 동일하면 OHLCV도 동일하므로 정합 문제 없음 (idempotent).
 *
 * profile 무관 — Redis는 mock·dev·prd 모두에서 사용.
 */
@Component
class ConsumerRedisUserCandleCacheAdapter(
    private val redis: StringRedisTemplate,
    private val mapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 캔들 스냅샷 목록을 Redis ZSet에 ZADD upsert한다.
     *
     * 같은 score(openTime) range를 먼저 삭제 후 ADD — api 어댑터와 동일 패턴으로 정합 보장.
     */
    fun upsertAll(
        userId: String,
        code: String,
        interval: String,
        snapshots: List<CandleSnapshot>,
    ) {
        if (snapshots.isEmpty()) return
        val key = candleKey(userId, code, interval)
        snapshots.forEach { snap ->
            val score = snap.openTime.epochSecond.toDouble()
            redis.opsForZSet().removeRangeByScore(key, score, score)
            val value =
                mapper.writeValueAsString(
                    mapOf(
                        "o" to snap.open.toPlainString(),
                        "h" to snap.high.toPlainString(),
                        "l" to snap.low.toPlainString(),
                        "c" to snap.close.toPlainString(),
                        "v" to snap.volume,
                        "tc" to 0L,
                        "itv" to interval,
                    ),
                )
            redis.opsForZSet().add(key, value, score)
        }
        log.debug(
            "[CONSUMER_CANDLE_CACHE] upsertAll userId={} code={} interval={} n={}",
            userId,
            code,
            interval,
            snapshots.size,
        )
    }

    private fun candleKey(
        userId: String,
        code: String,
        interval: String,
    ) = "candle:$userId:$code:$interval"
}

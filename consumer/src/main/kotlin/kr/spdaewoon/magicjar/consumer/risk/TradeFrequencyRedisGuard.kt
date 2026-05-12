package kr.spdaewoon.magicjar.consumer.risk

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.risk.port.TradeFrequencyGuard
import org.slf4j.LoggerFactory
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * 거래 빈도 sliding window 가드 — Redis ZSET 구현 (ADR 0067 §7-1 + D-1 정합).
 *
 * key:
 *   - `risk:trade-freq:{strategyId}:{userId}:minute`  (TTL 5분)
 *   - `risk:trade-freq:{strategyId}:{userId}:day`     (TTL 25시간 — 자정 reset cover)
 * score = epoch second · member = signalId.toString()
 * count = `ZCOUNT(key, now-window, now)` — O(log N).
 *
 * fail-open 정책 (Redis 결함 시 count 0 + 다른 룰로 안전망 cover).
 *
 * spec 인용: ADR 0067 §5-5 + §7-1 TradeFrequencyRedisGuard.
 *
 * // AI 호출 0 — T-RISK 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8)
 */
@Component
class TradeFrequencyRedisGuard(
    private val redisTemplate: StringRedisTemplate,
) : TradeFrequencyGuard {
    private val log = LoggerFactory.getLogger(TradeFrequencyRedisGuard::class.java)

    companion object {
        private const val KEY_PREFIX = "risk:trade-freq:"
        private const val MINUTE_WINDOW_SEC = 60L
        private const val DAY_WINDOW_SEC = 86400L

        private val MINUTE_KEY_TTL = Duration.ofMinutes(5)
        private val DAY_KEY_TTL = Duration.ofHours(25)
    }

    override fun countRecentMinute(
        strategyId: Long,
        userId: String,
        now: Instant,
    ): Int = count(buildKey(strategyId, userId, "minute"), now, MINUTE_WINDOW_SEC)

    override fun countRecentDay(
        strategyId: Long,
        userId: String,
        now: Instant,
    ): Int = count(buildKey(strategyId, userId, "day"), now, DAY_WINDOW_SEC)

    override fun record(
        strategyId: Long,
        userId: String,
        signalId: Long,
        now: Instant,
    ) {
        try {
            val score = now.epochSeconds.toDouble()
            val member = signalId.toString()
            val zops = redisTemplate.opsForZSet()

            val minuteKey = buildKey(strategyId, userId, "minute")
            zops.add(minuteKey, member, score)
            zops.removeRangeByScore(minuteKey, 0.0, score - MINUTE_WINDOW_SEC)
            redisTemplate.expire(minuteKey, MINUTE_KEY_TTL)

            val dayKey = buildKey(strategyId, userId, "day")
            zops.add(dayKey, member, score)
            zops.removeRangeByScore(dayKey, 0.0, score - DAY_WINDOW_SEC)
            redisTemplate.expire(dayKey, DAY_KEY_TTL)

            log.debug(
                "TradeFrequencyRedisGuard record: strategyId={} userId={} signalId={} ts={}",
                strategyId,
                userId,
                signalId,
                now,
            )
        } catch (e: Exception) {
            log.warn(
                "TradeFrequencyRedisGuard record 실패 (fail-open): strategyId={} userId={} err={}",
                strategyId,
                userId,
                e.message,
            )
        }
    }

    private fun count(
        key: String,
        now: Instant,
        windowSec: Long,
    ): Int =
        try {
            val nowEpoch = now.epochSeconds.toDouble()
            val fromEpoch = nowEpoch - windowSec
            val result = redisTemplate.opsForZSet().count(key, fromEpoch, nowEpoch)
            result?.toInt() ?: 0
        } catch (e: Exception) {
            log.warn("TradeFrequencyRedisGuard count 실패 (fail-open): key={} err={}", key, e.message)
            0
        }

    private fun buildKey(
        strategyId: Long,
        userId: String,
        window: String,
    ): String = "$KEY_PREFIX$strategyId:$userId:$window"
}

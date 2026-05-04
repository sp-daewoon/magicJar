package com.mj.consumer.messaging.listener

import com.mj.consumer.infrastructure.cache.ConsumerRedisUserCandleCacheAdapter
import com.mj.domain.market.event.UserCandleUpdatedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * market.candle.user.v1 → Redis ZADD (consumer 측). ADR 0050 R3-4.
 *
 * dual-write 1차:
 *   api:UserChartCandleService가 DB→Redis warm-up 직후 이 토픽에 publish한다.
 *   본 리스너가 수신 후 [ConsumerRedisUserCandleCacheAdapter.upsertAll]로 동일 ZADD를 수행한다.
 *   api 직접 ZADD와 consumer ZADD는 동일 Redis key에 같은 데이터를 쓴다 — idempotent.
 *
 * 다음 사이클(R3-5)에서 api 측 직접 ZADD를 제거하고 단일 path로 전환할 예정.
 *
 * 실패 처리:
 *   Redis 쓰기 실패 시 warn 로그 + 메시지 손실 허용 (best-effort). 사용자 차트는
 *   api 직접 ZADD로 이미 응답됐으므로 consumer ZADD 실패는 캐시 일관성 저하만 야기.
 */
@Component
class UserCandleListener(
    private val cacheAdapter: ConsumerRedisUserCandleCacheAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.userCandleUpdated:market.candle.user.v1}"],
        groupId = "magicjar-consumer-user-candle",
    )
    fun onUserCandleUpdated(event: UserCandleUpdatedEvent) {
        try {
            cacheAdapter.upsertAll(
                userId = event.userId,
                code = event.code,
                interval = event.interval,
                snapshots = event.candles,
            )
            log.debug(
                "[USER_CANDLE_LISTENER] ZADD userId={} code={} interval={} n={} occurredAt={}",
                event.userId,
                event.code,
                event.interval,
                event.candles.size,
                event.occurredAt,
            )
        } catch (ex: Exception) {
            log.warn(
                "[USER_CANDLE_LISTENER] ZADD failed userId={} code={} interval={} error={}",
                event.userId,
                event.code,
                event.interval,
                ex.message,
            )
        }
    }
}

package com.mj.consumer.application.aggregator

import com.mj.consumer.messaging.producer.CandleMinutePublisher
import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * market.tick.v1 → 1분봉 누적 → candle.minute.aggregated.v1 발행.
 *
 * ### 알고리즘
 * 1. tick 도착 시 해당 종목의 현재 분 버킷(MinuteBucket)에 누적.
 * 2. tick의 분이 버킷의 분보다 크면 "분 경계 초과" → 기존 버킷 flush(발행) + 새 버킷 생성.
 * 3. @Scheduled(fixedDelay=30s) timeout 스캔: 마지막 tick 이후 [BUCKET_TIMEOUT_MINUTES]분 이상
 *    tick 없는 버킷은 flush 후 제거 (메모리 누수 방지, 장 마감 처리).
 *
 * ### 조건부 활성
 * `magicjar.market.tick-aggregator.enabled=true` (기본 true — dev/prd 자동 활성).
 * mock profile에서 비활성화하려면 해당 yml에서 `enabled: false` 설정.
 *
 * ### Kafka 흐름
 * ```
 * market.tick.v1
 *   └─▶ TickToMinuteAggregator.onTick (분 경계 감지)
 *         └─▶ CandleMinutePublisher → candle.minute.aggregated.v1
 *               └─▶ api:CandleMinuteEventListener → DB INSERT (interval=M1)
 * ```
 */
@Component
@ConditionalOnProperty(
    prefix = "magicjar.market.tick-aggregator",
    name = ["enabled"],
    havingValue = "true",
    matchIfMissing = true,
)
class TickToMinuteAggregator(
    private val publisher: CandleMinutePublisher,
) {
    private val log = LoggerFactory.getLogger(TickToMinuteAggregator::class.java)

    /**
     * 종목별 현재 진행 중인 1분 버킷.
     * key = 종목코드, value = 현재 분의 MinuteBucket.
     */
    private val buckets = ConcurrentHashMap<String, MinuteBucket>()

    /**
     * market.tick.v1 수신 → 버킷 누적 + 분 경계 flush.
     *
     * stale tick(장외 fallback)도 버킷에 포함시키지 않는다 — `stale=true` 이면 무시.
     */
    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.tick:market.tick.v1}"],
        groupId = "\${magicjar.kafka.consumer-groups.tick-aggregator:magicjar-tick-aggregator}",
    )
    fun onTick(event: TickEvent) {
        // stale(장외 fallback) tick은 분봉 합성에서 제외
        if (event.stale) return

        val tickMinute = event.at.truncatedTo(ChronoUnit.MINUTES)

        buckets.compute(event.code) { code, existing ->
            when {
                existing == null -> {
                    // 첫 tick — 새 버킷 생성
                    MinuteBucket(code, tickMinute).also { it.add(event.price, event.volume, event.at) }
                }
                existing.openTime == tickMinute -> {
                    // 같은 분 — 누적
                    existing.also { it.add(event.price, event.volume, event.at) }
                }
                tickMinute > existing.openTime -> {
                    // 분 경계 초과 — 기존 버킷 flush 후 새 버킷
                    flushBucket(existing)
                    MinuteBucket(code, tickMinute).also { it.add(event.price, event.volume, event.at) }
                }
                else -> {
                    // 늦게 도착한 과거 분 tick (out-of-order) — 무시
                    log.debug(
                        "TickAggregator: out-of-order tick 무시 (code={}, tickMinute={}, bucketMinute={})",
                        code,
                        tickMinute,
                        existing.openTime,
                    )
                    existing
                }
            }
        }
    }

    /**
     * 30초마다 timeout 버킷 스캔.
     *
     * 마지막 tick 이후 [BUCKET_TIMEOUT_MINUTES]분 이상 tick이 없는 버킷을 flush + 제거.
     * 장 마감(15:30) 후 미처리 버킷이 남는 경우에 대비한 안전장치.
     */
    @Scheduled(fixedDelay = 30_000L)
    fun flushExpiredBuckets() {
        val now = Instant.now()
        val expiredCodes =
            buckets.entries
                .filter { (_, bucket) ->
                    Duration.between(bucket.lastTickAt, now) > BUCKET_TIMEOUT
                }.map { it.key }

        if (expiredCodes.isEmpty()) return

        for (code in expiredCodes) {
            buckets.remove(code)?.let { bucket ->
                if (bucket.hasData) {
                    log.info(
                        "TickAggregator: timeout flush (code={}, openTime={}, lastTick={})",
                        code,
                        bucket.openTime.atZone(KST),
                        bucket.lastTickAt.atZone(KST),
                    )
                    flushBucket(bucket)
                }
            }
        }
    }

    private fun flushBucket(bucket: MinuteBucket) {
        if (!bucket.hasData) return
        val event = bucket.toEvent()
        log.debug(
            "TickAggregator flush → candle (code={}, openTime={}, close={}, volume={})",
            event.code,
            event.openTime.atZone(KST),
            event.close,
            event.volume,
        )
        publisher.publish(event)
    }

    companion object {
        /** 마지막 tick 이후 이 시간이 지나면 버킷을 자동 flush + 제거. */
        private val BUCKET_TIMEOUT: Duration = Duration.ofMinutes(5)
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
    }
}

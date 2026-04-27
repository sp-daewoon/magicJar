package com.mj.consumer.messaging.listener

import com.mj.consumer.messaging.producer.CandlePublisher
import com.mj.domain.market.event.CandleEvent
import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.ConcurrentHashMap

/**
 * market.tick.v1 → 1분봉 집계 → CandlePublisher.
 *
 * 단순 인-메모리 집계: 같은 code + 분 버킷에 tick을 누적.
 * 버킷이 닫히면(다음 분 tick 도착 시) CandleEvent 발행.
 */
@Component
class TickAggregatorListener(
    private val candlePublisher: CandlePublisher,
) {
    private val log = LoggerFactory.getLogger(TickAggregatorListener::class.java)

    // key = "code|minuteBucket"
    private val buckets = ConcurrentHashMap<String, TickBucket>()

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.tick:market.tick.v1}"],
        groupId = "\${spring.kafka.consumer.group-id:magicjar-consumer}",
    )
    fun onTick(event: TickEvent) {
        val bucketKey = "${event.code}|${event.at.truncatedTo(ChronoUnit.MINUTES)}"
        val bucket = buckets.getOrPut(bucketKey) { TickBucket(event.code, event.at.truncatedTo(ChronoUnit.MINUTES)) }
        bucket.add(event.price, event.volume)

        // 이전 분 버킷 플러시
        val expiredKeys =
            buckets.keys.filter { k ->
                val parts = k.split("|")
                parts[0] == event.code && k != bucketKey
            }
        expiredKeys.forEach { key ->
            buckets.remove(key)?.let { expired ->
                val candle = expired.toEvent()
                log.debug("TickAggregator flush candle (code={}, openTime={})", candle.code, candle.openTime)
                candlePublisher.publish(candle)
            }
        }
    }

    private inner class TickBucket(
        val code: String,
        val openTime: Instant,
    ) {
        var open: BigDecimal? = null
        var high: BigDecimal = BigDecimal.ZERO
        var low: BigDecimal = BigDecimal(Long.MAX_VALUE)
        var close: BigDecimal = BigDecimal.ZERO
        var volume: Long = 0L
        var tradeCount: Long = 0L

        fun add(
            price: BigDecimal,
            vol: Long,
        ) {
            if (open == null) open = price
            if (price > high) high = price
            if (price < low) low = price
            close = price
            volume += vol
            tradeCount++
        }

        fun toEvent(): CandleEvent =
            CandleEvent(
                code = code,
                interval = "1m",
                openTime = openTime,
                open = open ?: close,
                high = high,
                low = if (low == BigDecimal(Long.MAX_VALUE)) close else low,
                close = close,
                volume = volume,
                tradeCount = tradeCount,
            )
    }
}

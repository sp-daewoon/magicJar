package kr.spdaewoon.magicjar.marketdata.kis

import io.github.bucket4j.Bandwidth
import io.github.bucket4j.Bucket
import io.github.bucket4j.Refill
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Duration

/**
 * KIS REST 호출용 글로벌 레이트리미터.
 *
 * - 시세/일반 호출: 초당 capacity (기본 15회) — 공식 20회 한도에 5회 버퍼.
 * - 주문 계열: 초당 orderCapacity (기본 5회) — 별도 버킷.
 * - 토큰 부족 시 최대 2초 대기 후 실패.
 */
@Component
class KisRateLimiter(
    properties: KisProperties,
) {
    private val log = LoggerFactory.getLogger(KisRateLimiter::class.java)

    private val quoteBucket: Bucket =
        Bucket
            .builder()
            .addLimit(
                Bandwidth.classic(
                    properties.rate.capacityPerSecond.toLong(),
                    Refill.intervally(properties.rate.capacityPerSecond.toLong(), Duration.ofSeconds(1)),
                ),
            ).build()

    private val orderBucket: Bucket =
        Bucket
            .builder()
            .addLimit(
                Bandwidth.classic(
                    properties.rate.orderPerSecond.toLong(),
                    Refill.intervally(properties.rate.orderPerSecond.toLong(), Duration.ofSeconds(1)),
                ),
            ).build()

    fun acquireQuote() = acquire(quoteBucket, "quote")

    fun acquireOrder() = acquire(orderBucket, "order")

    private fun acquire(
        bucket: Bucket,
        category: String,
    ) {
        val probe = bucket.tryConsumeAndReturnRemaining(1)
        if (probe.isConsumed) return
        val waitNanos = probe.nanosToWaitForRefill.coerceAtMost(Duration.ofSeconds(2).toNanos())
        log.warn("KIS rate limit hit (category={}), waiting {}ms", category, waitNanos / 1_000_000)
        if (waitNanos > 0) {
            Thread.sleep(waitNanos / 1_000_000, (waitNanos % 1_000_000).toInt())
        }
        if (!bucket.tryConsume(1)) {
            error("KIS rate limit exceeded (category=$category)")
        }
    }
}

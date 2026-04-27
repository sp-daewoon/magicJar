package com.mj.consumer.infrastructure.kis

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * KIS REST 호출용 글로벌 레이트리미터 (토큰 버킷 간이 구현).
 *
 * - 시세/일반 호출: 초당 capacity (기본 15회)
 * - 주문 계열: 초당 orderCapacity (기본 5회)
 */
@Component
class KisRateLimiter(
    properties: KisProperties,
) {
    private val log = LoggerFactory.getLogger(KisRateLimiter::class.java)

    private val quotePermits = SimpleRateLimiter(properties.rate.capacityPerSecond)
    private val orderPermits = SimpleRateLimiter(properties.rate.orderPerSecond)

    fun acquireQuote() = quotePermits.acquire("quote")

    fun acquireOrder() = orderPermits.acquire("order")

    private inner class SimpleRateLimiter(
        private val perSecond: Int,
    ) {
        private val windowMs = 1000L
        private val semaphore = Semaphore(perSecond)
        private val lastRefillAt = AtomicLong(System.currentTimeMillis())

        fun acquire(category: String) {
            refillIfNeeded()
            if (!semaphore.tryAcquire(2000, TimeUnit.MILLISECONDS)) {
                error("KIS rate limit exceeded (category=$category)")
            }
            log.debug("KIS rate limiter acquired (category={}, remaining={})", category, semaphore.availablePermits())
        }

        private fun refillIfNeeded() {
            val now = System.currentTimeMillis()
            val last = lastRefillAt.get()
            if (now - last >= windowMs) {
                if (lastRefillAt.compareAndSet(last, now)) {
                    val current = semaphore.availablePermits()
                    val toAdd = (perSecond - current).coerceAtLeast(0)
                    if (toAdd > 0) semaphore.release(toAdd)
                }
            }
        }
    }
}

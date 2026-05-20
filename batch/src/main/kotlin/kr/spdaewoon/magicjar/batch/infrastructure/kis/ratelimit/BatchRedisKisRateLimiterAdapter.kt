package kr.spdaewoon.magicjar.batch.infrastructure.kis.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.slf4j.LoggerFactory
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * batch 모듈 KIS Rate Limit Bucket4j-Redis 단일 bucket adapter (spec PR #564 §2-3 옵션 a 정합).
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 (본 adapter가 §2-1 메커니즘 권위)
 *   - ADR 0038 — Redis 인프라 재사용 (multi-JVM 단일 bucket share)
 *
 * 책임 (api·consumer 모듈 RedisKisRateLimiterAdapter와 동일 알고리즘 — 옵션 a 정합):
 *   - Bucket4j 8.10.1 + Lettuce Redis backend로 단일 글로벌 bucket 발행 (api·consumer Bean과 동일 bucketKey LOCK)
 *   - `acquire(priority, timeoutMillis)` blocking polling 패턴 (tryConsume non-blocking + short sleep retry)
 *   - EGW00201/HTTP 429 시그널 누적 (Phase 1 log 카운터만 — Phase 3 Circuit Breaker 합류 후속)
 *
 * Phase 1 LOCK:
 *   - 모든 priority 동일 bucket (USER_PATH > INGESTION > BACKFILL 라우팅 0)
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회)
 *   - batch 3 어댑터(KisChkHolidayAdapter·KisInstrumentMasterAdapter·KisKsdInfoAdapter) 합류 후 KIS 차단 risk 영구 차단
 */
class BatchRedisKisRateLimiterAdapter(
    private val proxyManager: ProxyManager<String>,
    private val props: BatchKisRateLimitProperties,
) : KisRateLimiterPort {
    private val log = LoggerFactory.getLogger(BatchRedisKisRateLimiterAdapter::class.java)

    /** Phase 1 통계 카운터 (Phase 3 Circuit Breaker에서 권위 위임) */
    private val tpsExceededCount = AtomicLong(0L)
    private val successCount = AtomicLong(0L)

    /**
     * Bucket4j BucketConfiguration — Greedy refill (capacity 도달 시 일정 간격 회복).
     * Phase 1 LOCK: 10 token / 1 sec greedy.
     */
    private val bucketConfiguration: BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit { limit ->
                limit
                    .capacity(props.bucketCapacity)
                    .refillGreedy(
                        props.bucketRefillTokens,
                        Duration.ofSeconds(props.bucketRefillPeriodSeconds),
                    )
            }.build()

    /**
     * 단일 글로벌 bucket — Redis backend Lettuce ProxyManager가 share 보장.
     * lazy 초기화: ProxyManager Bean이 ApplicationContext 부팅 후 가용해야 함.
     */
    private val bucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey) { bucketConfiguration }
    }

    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking polling).
     */
    override fun acquire(
        priority: KisCallPriority,
        timeoutMillis: Long,
    ) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        var attempts = 0

        while (true) {
            attempts++
            if (bucket.tryConsume(1L)) {
                if (attempts > 1) {
                    log.debug(
                        "[KisRateLimit/batch] acquire OK after polling — priority={} attempts={} elapsedMs={}",
                        priority,
                        attempts,
                        (System.nanoTime() - (deadlineNanos - timeoutMillis * 1_000_000L)) / 1_000_000L,
                    )
                }
                return
            }
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                log.warn(
                    "[KisRateLimit/batch] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={}",
                    priority,
                    timeoutMillis,
                    attempts,
                    props.bucketKey,
                )
                throw KisRateLimitTimeoutException(priority, timeoutMillis)
            }
            val sleepMillis = props.acquirePollIntervalMillis.coerceAtMost(remainingNanos / 1_000_000L)
            if (sleepMillis > 0L) {
                // TODO: virtual thread 도입 시 LockSupport.parkNanos 또는 BucketProxy.asScheduler() 합류 — ADR 0037 Phase 3 후속
                Thread.sleep(sleepMillis)
            }
        }
    }

    /** EGW00201 / HTTP 429 응답 시그널 (Phase 1: log + 카운터만). */
    override fun onTpsExceeded() {
        val count = tpsExceededCount.incrementAndGet()
        log.warn(
            "[KisRateLimit/batch] TPS exceeded signal — count={} (Phase 3 Circuit Breaker 합류 대기)",
            count,
        )
    }

    /** 정상 응답 시그널 (Phase 1: log debug + 카운터만 — fail rate 분모 누적). */
    override fun onSuccess() {
        val count = successCount.incrementAndGet()
        if (count % 100L == 0L) {
            log.debug(
                "[KisRateLimit/batch] success milestone — count={} tpsExceeded={}",
                count,
                tpsExceededCount.get(),
            )
        }
    }
}

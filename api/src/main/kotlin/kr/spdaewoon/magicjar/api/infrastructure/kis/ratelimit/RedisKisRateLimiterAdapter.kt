package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

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
 * KIS Rate Limit Bucket4j-Redis 단일 bucket adapter (spec PR #564 §2-1 옵션 A LOCK).
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 (본 adapter가 §2-1 메커니즘 권위)
 *   - ADR 0038 — Redis 인프라 재사용 (multi-JVM 단일 bucket share)
 *
 * 책임:
 *   - Bucket4j 8.10.1 + Lettuce Redis backend로 단일 글로벌 bucket 발행
 *   - `acquire(priority, timeoutMillis)` blocking polling 패턴 (tryConsume non-blocking + short sleep retry)
 *   - EGW00201/HTTP 429 시그널 누적 (Phase 1 log 카운터만 — Phase 3 Circuit Breaker 합류 후속)
 *
 * Phase 1 LOCK:
 *   - 모든 priority 동일 bucket (USER_PATH > INGESTION > BACKFILL 라우팅 0)
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회 — Phase 2 합류 후 강제)
 *   - Phase 2 어댑터 통합 후 KIS 차단 risk 영구 차단
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 `tryConsume(1)` non-blocking + polling 패턴 — BlockingBucket 미지원 distributed proxy 회피
 *   - 🔄 Bucket4j 8.x ProxyManager Lettuce backend 채택 (Redisson 의존 0 — bundle bloat 회피)
 *   - ✨ 통계 카운터는 AtomicLong (Phase 3 Resilience4j Circuit Breaker 합류 시 위임)
 */
class RedisKisRateLimiterAdapter(
    private val proxyManager: ProxyManager<String>,
    private val props: KisRateLimitProperties,
) : KisRateLimiterPort {
    private val log = LoggerFactory.getLogger(RedisKisRateLimiterAdapter::class.java)

    /** Phase 1 통계 카운터 (Phase 3 Circuit Breaker에서 권위 위임) */
    private val tpsExceededCount = AtomicLong(0L)
    private val successCount = AtomicLong(0L)

    /**
     * Bucket4j BucketConfiguration — Greedy refill (capacity 도달 시 일정 간격 회복).
     *
     * Phase 1 LOCK: 10 token / 1 sec greedy.
     *   - greedy = "1초에 10번 작은 step refill" (10 token이 1초마다 한 번에 한 게 아니라 100ms마다 1 token)
     *   - KIS 한도 정합 + smooth burst 흡수 (사용자 path 단발 호출 시 즉시 acquire 성공)
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
     *
     * lazy 초기화: ProxyManager Bean이 ApplicationContext 부팅 후 가용해야 함.
     */
    private val bucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey) { bucketConfiguration }
    }

    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking polling).
     *
     * 알고리즘 (Phase 1 단순화 LOCK):
     *   1. `bucket.tryConsume(1)` non-blocking 호출
     *   2. 성공 → 즉시 return
     *   3. 실패 → polling interval(50ms) sleep 후 재시도
     *   4. timeoutMillis 누적 초과 → throw [KisRateLimitTimeoutException]
     *
     * Phase 3 후속 최적화 후보:
     *   - BucketProxy `asScheduler().tryConsume(1, maxWait, scheduler)` 합류 (ScheduledExecutorService 의존)
     *   - 우선순위 큐 합류 (USER_PATH 즉시 / INGESTION 100ms / BACKFILL 500ms polling)
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
                        "[KisRateLimit] acquire OK after polling — priority={} attempts={} elapsedMs={}",
                        priority,
                        attempts,
                        (System.nanoTime() - (deadlineNanos - timeoutMillis * 1_000_000L)) / 1_000_000L,
                    )
                }
                return
            }
            // token 부족 → polling 또는 timeout
            val remainingNanos = deadlineNanos - System.nanoTime()
            if (remainingNanos <= 0L) {
                log.warn(
                    "[KisRateLimit] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={}",
                    priority,
                    timeoutMillis,
                    attempts,
                    props.bucketKey,
                )
                throw KisRateLimitTimeoutException(priority, timeoutMillis)
            }
            val sleepMillis = props.acquirePollIntervalMillis.coerceAtMost(remainingNanos / 1_000_000L)
            if (sleepMillis > 0L) {
                Thread.sleep(sleepMillis)
            }
        }
    }

    /**
     * EGW00201 / HTTP 429 응답 시그널 (Phase 1: log + 카운터만).
     *
     * Phase 3 합류 시 Resilience4j Circuit Breaker `onError()` 위임 → 5분 윈도 50% 임계 도달 시 OPEN.
     */
    override fun onTpsExceeded() {
        val count = tpsExceededCount.incrementAndGet()
        log.warn(
            "[KisRateLimit] TPS exceeded signal — count={} (Phase 3 Circuit Breaker 합류 대기)",
            count,
        )
    }

    /**
     * 정상 응답 시그널 (Phase 1: log debug + 카운터만 — fail rate 분모 누적).
     */
    override fun onSuccess() {
        val count = successCount.incrementAndGet()
        if (count % 100L == 0L) {
            log.debug(
                "[KisRateLimit] success milestone — count={} tpsExceeded={}",
                count,
                tpsExceededCount.get(),
            )
        }
    }

    /** 테스트·모니터링 진입점 (Phase 3 Circuit Breaker에서 권위 위임) */
    internal fun snapshotTpsExceeded(): Long = tpsExceededCount.get()

    internal fun snapshotSuccess(): Long = successCount.get()
}

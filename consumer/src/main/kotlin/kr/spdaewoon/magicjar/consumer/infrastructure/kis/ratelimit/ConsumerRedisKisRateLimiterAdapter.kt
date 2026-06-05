package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

import io.github.bucket4j.BucketConfiguration
import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimiterPort
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * consumer 모듈 KIS Rate Limit Bucket4j-Redis 단일 bucket adapter (spec PR #564 §2-3 옵션 a 정합).
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 (본 adapter가 §2-1 메커니즘 권위)
 *   - ADR 0038 — Redis 인프라 재사용 (multi-JVM 단일 bucket share)
 *
 * 책임 (api 모듈 RedisKisRateLimiterAdapter와 동일 알고리즘 — 옵션 a 정합):
 *   - Bucket4j 8.10.1 + Lettuce Redis backend로 단일 글로벌 bucket 발행 (api 모듈 Bean과 동일 bucketKey LOCK)
 *   - `acquire(priority, timeoutMillis)` blocking polling 패턴 (tryConsume non-blocking + short sleep retry)
 *   - EGW00201/HTTP 429 시그널 누적 (Phase 1 log 카운터만 — Phase 3 Circuit Breaker 합류 후속)
 *
 * priority 차등 라우팅 (ADR 0112 의제 — 2026-06-05 주문 path 보호, api 모듈 RedisKisRateLimiterAdapter 동일):
 *   - shared bucket (capacity = bucketCapacity - userPathReserveTokens): 모든 priority 1차 소비
 *   - reserve bucket (capacity = userPathReserveTokens): USER_PATH 전용 fallback (shared 소진 시)
 *   - consumer 모듈은 본주문(KisOrderHttpAdapter USER_PATH)이 흐르는 핵심 경로 — reserve 직접 수혜
 *   - 두 bucket 합 = bucketCapacity (KIS 초당 한도 미초과). userPathReserveTokens=0 시 단일 bucket 회귀.
 *
 * Circuit Breaker LOCK:
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회)
 *   - reserve 라우팅으로 비주문 burst 가 주문 path 를 굶기는 EGW00201 직격 risk 차단 (14:59 evidence)
 */
class ConsumerRedisKisRateLimiterAdapter(
    private val proxyManager: ProxyManager<String>,
    private val props: ConsumerKisRateLimitProperties,
    /**
     * SystemEvent emit port (진단 PR #639 P0 hotfix — KIS_RATELIMIT P3 catalog → P1 활성).
     *
     * nullable LOCK 사유 — 단위 테스트 허용 + Bean wiring 단계 분리.
     */
    private val systemEventLogger: SystemEventLogger? = null,
) : KisRateLimiterPort {
    private val log = LoggerFactory.getLogger(ConsumerRedisKisRateLimiterAdapter::class.java)

    /** Phase 1 통계 카운터 (Phase 3 Circuit Breaker에서 권위 위임) */
    private val tpsExceededCount = AtomicLong(0L)
    private val successCount = AtomicLong(0L)

    /** SystemEvent emit aggregator — 5분 윈도 1회 LOCK (spam 회피) */
    private val lastEmittedAt = AtomicReference<Instant>(Instant.EPOCH)

    private companion object {
        val EMIT_AGGREGATOR_WINDOW: Duration = Duration.ofMinutes(5)
        const val ACTOR = "ConsumerRedisKisRateLimiterAdapter"
        const val ACTION_TPS_EXCEEDED = "KIS_TPS_EXCEEDED"

        /** USER_PATH 전용 reserve bucket key suffix (api 모듈과 동일 LOCK — 동일 Redis key 분할 정합). */
        const val RESERVE_KEY_SUFFIX = ":user-reserve"
    }

    /** reserve 토큰 수 — bucketCapacity 로 clamp (음수·초과 방지). 0 이면 단일 bucket 회귀. */
    private val reserveTokens: Long = props.userPathReserveTokens.coerceIn(0L, props.bucketCapacity)

    /** shared pool capacity = bucketCapacity - reserve (비주문·USER_PATH 1차 공용). */
    private val sharedCapacity: Long = props.bucketCapacity - reserveTokens

    /** reserve 라우팅 활성 여부 (reserve > 0 이고 shared > 0 일 때만). */
    private val reserveEnabled: Boolean = reserveTokens > 0L && sharedCapacity > 0L

    private fun bucketConfigFor(
        capacity: Long,
        refill: Long,
    ): BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit { limit ->
                limit
                    .capacity(capacity)
                    .refillIntervally(refill, Duration.ofSeconds(props.bucketRefillPeriodSeconds))
            }.build()

    /**
     * shared bucket — 모든 priority 1차 소비. reserve 활성 시 capacity = bucketCapacity - reserve.
     * Redis backend Lettuce ProxyManager 가 multi-JVM share 보장 (api·batch 동일 bucketKey).
     */
    private val sharedBucket: BucketProxy by lazy {
        val cap = if (reserveEnabled) sharedCapacity else props.bucketCapacity
        proxyManager.builder().build(props.bucketKey) { bucketConfigFor(cap, cap) }
    }

    /** reserve bucket — USER_PATH 전용 fallback (shared 소진 시). reserve 비활성 시 미발행. */
    private val reserveBucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey + RESERVE_KEY_SUFFIX) {
            bucketConfigFor(reserveTokens, reserveTokens)
        }
    }

    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking polling).
     *
     * 차등 라우팅 알고리즘 (ADR 0112 의제):
     *   1. shared bucket `tryConsume(1)` 시도 → 성공 시 return
     *   2. (USER_PATH + reserve 활성) shared 실패 → reserve bucket `tryConsume(1)` → 성공 시 return
     *   3. 둘 다 실패 → polling interval sleep 후 재시도
     *   4. timeoutMillis 초과 → throw [KisRateLimitTimeoutException]
     */
    override fun acquire(
        priority: KisCallPriority,
        timeoutMillis: Long,
    ) {
        val deadlineNanos = System.nanoTime() + timeoutMillis * 1_000_000L
        var attempts = 0

        while (true) {
            attempts++
            if (tryConsumeRouted(priority)) {
                if (attempts > 1) {
                    log.debug(
                        "[KisRateLimit/consumer] acquire OK after polling — priority={} attempts={} elapsedMs={}",
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
                    "[KisRateLimit/consumer] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={} reserveEnabled={}",
                    priority,
                    timeoutMillis,
                    attempts,
                    props.bucketKey,
                    reserveEnabled,
                )
                throw KisRateLimitTimeoutException(priority, timeoutMillis)
            }
            val sleepMillis = props.acquirePollIntervalMillis.coerceAtMost(remainingNanos / 1_000_000L)
            if (sleepMillis > 0L) {
                // TODO: virtual thread 도입 시 LockSupport.parkNanos 또는 BucketProxy.asScheduler() 합류 — ADR 0037 Phase 3 후속
                // 현 platform thread 환경에서 Thread.sleep은 안전하나, JDK 21 virtual thread 합류 시
                // pinning risk 회피 위해 java.util.concurrent.locks.LockSupport.parkNanos(nanos) 또는
                // Bucket4j의 BucketProxy.asScheduler() (CompletableFuture 기반 non-blocking) 전환 의무.
                Thread.sleep(sleepMillis)
            }
        }
    }

    /**
     * priority 차등 단발 소비 (non-blocking) — shared 1차, USER_PATH 는 reserve fallback.
     */
    private fun tryConsumeRouted(priority: KisCallPriority): Boolean {
        if (sharedBucket.tryConsume(1L)) return true
        if (reserveEnabled && priority == KisCallPriority.USER_PATH) {
            return reserveBucket.tryConsume(1L)
        }
        return false
    }

    /**
     * EGW00201 / HTTP 429 응답 시그널 — 통계 누적 + SystemEvent emit (P0 hotfix).
     *
     * ### 진단 PR #639 P0 hotfix
     * - SystemEventLogger.emit(KIS_RATELIMIT, WARN) 합류 — `/system` 페이지 가시화
     * - aggregator 5분 LOCK — spam 회피 (EGW00201 burst 시 1건만 발사)
     */
    override fun onTpsExceeded() {
        val count = tpsExceededCount.incrementAndGet()
        log.warn(
            "[KisRateLimit/consumer] TPS exceeded signal — count={} (Phase 3 Circuit Breaker 합류 대기)",
            count,
        )
        emitSystemEventIfThresholdReached(count)
    }

    /**
     * SystemEvent emit aggregator — 5분 윈도 1회 LOCK (spam 회피, api·batch 동일 패턴).
     */
    private fun emitSystemEventIfThresholdReached(currentCount: Long) {
        val logger = systemEventLogger ?: return
        val now = Instant.now()
        val prev = lastEmittedAt.get()
        if (Duration.between(prev, now) < EMIT_AGGREGATOR_WINDOW) {
            return
        }
        if (!lastEmittedAt.compareAndSet(prev, now)) {
            return
        }

        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.KIS_RATELIMIT,
                    severity = SystemEventSeverity.WARN,
                    actor = ACTOR,
                    action = ACTION_TPS_EXCEEDED,
                    userId = null,
                    correlationId = null,
                    detailJson =
                        """{"tpsExceededCount":$currentCount,"bucketKey":"${props.bucketKey}",""" +
                            """"aggregatorWindowMinutes":5,"module":"consumer"}""",
                ),
            )
            log.warn(
                "[KisRateLimit/consumer] SystemEvent KIS_RATELIMIT emit — count={} window=5min",
                currentCount,
            )
        }.onFailure { e ->
            log.error(
                "[KisRateLimit/consumer] SystemEvent emit 실패 — count={} err={}",
                currentCount,
                e.message,
            )
        }
    }

    /** 정상 응답 시그널 (Phase 1: log debug + 카운터만 — fail rate 분모 누적). */
    override fun onSuccess() {
        val count = successCount.incrementAndGet()
        if (count % 100L == 0L) {
            log.debug(
                "[KisRateLimit/consumer] success milestone — count={} tpsExceeded={}",
                count,
                tpsExceededCount.get(),
            )
        }
    }

    /** 테스트·모니터링 진입점 (Phase 3 Circuit Breaker에서 권위 위임) */
    internal fun snapshotTpsExceeded(): Long = tpsExceededCount.get()

    internal fun snapshotSuccess(): Long = successCount.get()
}

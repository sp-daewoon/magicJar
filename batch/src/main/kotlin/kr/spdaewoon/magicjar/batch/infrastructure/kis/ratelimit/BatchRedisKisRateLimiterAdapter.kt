package kr.spdaewoon.magicjar.batch.infrastructure.kis.ratelimit

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
 * batch 모듈 KIS Rate Limit Bucket4j-Redis 3-tier priority 버킷 adapter (ADR 0116 §2-B Phase 2).
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 (ADR 0116이 단일 bucket → 3-tier supersede)
 *   - ADR 0038 — Redis 인프라 재사용 (multi-JVM 버킷 share)
 *   - ADR 0116 — priority별 별도 버킷(3-tier) + 역방향 fallback
 *
 * 책임 (api·consumer 모듈 RedisKisRateLimiterAdapter와 동일 알고리즘):
 *   - Bucket4j 8.10.1 + Lettuce Redis backend로 priority별 버킷 발행 (api·consumer와 동일 bucketKey LOCK)
 *   - `acquire(priority, timeoutMillis)` blocking polling 패턴 (tryConsume non-blocking + short sleep retry)
 *   - EGW00201/HTTP 429 시그널 누적 (Phase 1 log 카운터만 — Phase 3 Circuit Breaker 합류 후속)
 *
 * priority별 별도 버킷 (3-tier · ADR 0116 §2-B · api·consumer 동일):
 *   - USER 버킷(capacity=bucketUser): batch는 USER_PATH 0 → 미접근 (보존만)
 *   - SHARED 버킷(capacity=bucketShared): INGESTION 1차
 *   - BG 버킷(capacity=bucketBg): BACKFILL·INGESTION_LOW 1차 (batch 캔들 backfill·corp action)
 *   - 역방향 fallback: USER_PATH→[user,shared,bg]·INGESTION→[shared,bg]·BG→[bg]
 *   - ★ 각 버킷 capacity를 api·consumer와 동일하게 맞춰야 동일 Redis 버킷 config 정합 (불일치 시 first-writer-wins 충돌)
 *   - priorityBucketsEnabled=false 시 단일 :global 버킷 회귀
 *
 * Circuit Breaker LOCK:
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회)
 *   - batch 3 어댑터(KisChkHolidayAdapter·KisInstrumentMasterAdapter·KisKsdInfoAdapter) 합류 후 KIS 차단 risk 영구 차단
 */
class BatchRedisKisRateLimiterAdapter(
    private val proxyManager: ProxyManager<String>,
    private val props: BatchKisRateLimitProperties,
    /**
     * SystemEvent emit port (진단 PR #639 P0 hotfix — KIS_RATELIMIT P3 catalog → P1 활성).
     *
     * nullable LOCK 사유 — 단위 테스트 허용 + Bean wiring 단계 분리.
     */
    private val systemEventLogger: SystemEventLogger? = null,
) : KisRateLimiterPort {
    private val log = LoggerFactory.getLogger(BatchRedisKisRateLimiterAdapter::class.java)

    /** Phase 1 통계 카운터 (Phase 3 Circuit Breaker에서 권위 위임) */
    private val tpsExceededCount = AtomicLong(0L)
    private val successCount = AtomicLong(0L)

    /** SystemEvent emit aggregator — 5분 윈도 1회 LOCK (spam 회피) */
    private val lastEmittedAt = AtomicReference<Instant>(Instant.EPOCH)

    private companion object {
        val EMIT_AGGREGATOR_WINDOW: Duration = Duration.ofMinutes(5)
        const val ACTOR = "BatchRedisKisRateLimiterAdapter"
        const val ACTION_TPS_EXCEEDED = "KIS_TPS_EXCEEDED"

        /** priority별 버킷 key suffix (api·consumer 동일 LOCK — 동일 Redis 3 key 정합 · ADR 0116 §2-B). */
        const val USER_KEY_SUFFIX = ":user"
        const val SHARED_KEY_SUFFIX = ":shared"
        const val BG_KEY_SUFFIX = ":bg"
    }

    /** priority별 버킷 capacity 합 = bucketCapacity 인지 (ADR 0116 §4 합 검증). 불일치 시 단일 bucket fallback. */
    private val bucketSumValid: Boolean =
        (props.bucketUser + props.bucketShared + props.bucketBg) == props.bucketCapacity

    /** 3-tier priority 버킷 활성 여부. 비활성 시 단일 :global 버킷(capacity) 회귀 (Phase 1 안전 최소). */
    private val priorityBucketsActive: Boolean =
        props.priorityBucketsEnabled &&
            bucketSumValid &&
            props.bucketUser > 0L &&
            props.bucketShared > 0L &&
            props.bucketBg > 0L

    init {
        if (props.priorityBucketsEnabled && !bucketSumValid) {
            log.warn(
                "[KisRateLimit/batch] priority 버킷 합 불일치 — 단일 bucket fallback: user={} shared={} bg={} sum={} != capacity={}",
                props.bucketUser,
                props.bucketShared,
                props.bucketBg,
                props.bucketUser + props.bucketShared + props.bucketBg,
                props.bucketCapacity,
            )
        }
    }

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

    private fun buildBucket(
        keySuffix: String,
        capacity: Long,
    ): BucketProxy = proxyManager.builder().build(props.bucketKey + keySuffix) { bucketConfigFor(capacity, capacity) }

    /** USER 버킷 — batch는 USER_PATH 0이라 미접근. Redis backend가 multi-JVM share 보장 (api·consumer 동일 bucketKey). */
    private val userBucket: BucketProxy by lazy { buildBucket(USER_KEY_SUFFIX, props.bucketUser) }

    /** SHARED 버킷 — INGESTION 1차. */
    private val sharedBucket: BucketProxy by lazy { buildBucket(SHARED_KEY_SUFFIX, props.bucketShared) }

    /** BG 버킷 — BACKFILL·INGESTION_LOW 1차 (batch 주 소비 버킷). */
    private val bgBucket: BucketProxy by lazy { buildBucket(BG_KEY_SUFFIX, props.bucketBg) }

    /** 단일 :global 버킷 — priorityBucketsActive=false 회귀 path (Phase 1). */
    private val globalBucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey) { bucketConfigFor(props.bucketCapacity, props.bucketCapacity) }
    }

    /**
     * priority → 접근 가능 버킷 리스트 (역방향 fallback · ADR 0116 §2-B).
     * USER_PATH→[USER,SHARED,BG]·INGESTION→[SHARED,BG]·BACKFILL/INGESTION_LOW→[BG] (when exhaustive).
     */
    private fun bucketsFor(priority: KisCallPriority): List<BucketProxy> =
        when (priority) {
            KisCallPriority.USER_PATH -> listOf(userBucket, sharedBucket, bgBucket)
            KisCallPriority.INGESTION -> listOf(sharedBucket, bgBucket)
            KisCallPriority.BACKFILL, KisCallPriority.INGESTION_LOW -> listOf(bgBucket)
        }

    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking polling).
     *
     * 라우팅 (ADR 0116 §2-B): priority별 접근 가능 버킷 리스트 순회.
     * batch 는 USER_PATH 0 → INGESTION([shared,bg]) 또는 BACKFILL/INGESTION_LOW([bg])만 소비.
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
                    "[KisRateLimit/batch] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={} priorityBucketsActive={}",
                    priority,
                    timeoutMillis,
                    attempts,
                    props.bucketKey,
                    priorityBucketsActive,
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

    /**
     * priority 라우팅 단발 소비 (non-blocking) — 접근 가능 버킷 리스트 순회 (ADR 0116 §2-B).
     * priorityBucketsActive=false 시 단일 :global 버킷만 소비 (Phase 1 회귀).
     */
    private fun tryConsumeRouted(priority: KisCallPriority): Boolean {
        if (!priorityBucketsActive) return globalBucket.tryConsume(1L)
        return bucketsFor(priority).any { it.tryConsume(1L) }
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
            "[KisRateLimit/batch] TPS exceeded signal — count={} (Phase 3 Circuit Breaker 합류 대기)",
            count,
        )
        emitSystemEventIfThresholdReached(count)
    }

    /**
     * SystemEvent emit aggregator — 5분 윈도 1회 LOCK (spam 회피, api·consumer 동일 패턴).
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
                            """"aggregatorWindowMinutes":5,"module":"batch"}""",
                ),
            )
            log.warn(
                "[KisRateLimit/batch] SystemEvent KIS_RATELIMIT emit — count={} window=5min",
                currentCount,
            )
        }.onFailure { e ->
            log.error(
                "[KisRateLimit/batch] SystemEvent emit 실패 — count={} err={}",
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
                "[KisRateLimit/batch] success milestone — count={} tpsExceeded={}",
                count,
                tpsExceededCount.get(),
            )
        }
    }
}

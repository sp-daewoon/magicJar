package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

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
 * KIS Rate Limit Bucket4j-Redis 3-tier priority 버킷 adapter (ADR 0116 §2-B Phase 2).
 *
 * 정합 ADR:
 *   - ADR 0037 — Rate Limit 강제 (ADR 0116이 단일 bucket → 3-tier supersede)
 *   - ADR 0038 — Redis 인프라 재사용 (multi-JVM 버킷 share)
 *   - ADR 0116 — priority별 별도 버킷(3-tier) + 역방향 fallback + KIS 한도 정합
 *
 * 책임:
 *   - Bucket4j 8.10.1 + Lettuce Redis backend로 priority별 버킷 발행
 *   - `acquire(priority, timeoutMillis)` blocking polling 패턴 (tryConsume non-blocking + short sleep retry)
 *   - EGW00201/HTTP 429 시그널 누적 (Phase 1 log 카운터만 — Phase 3 Circuit Breaker 합류 후속)
 *
 * priority별 별도 버킷 (3-tier · ADR 0116 §2-B):
 *   - USER 버킷(capacity=bucketUser=4): USER_PATH 1차 — 주문·현재가·차트·잔고
 *   - SHARED 버킷(capacity=bucketShared=3): INGESTION 1차 — watchlist·approval
 *   - BG 버킷(capacity=bucketBg=3): BACKFILL·INGESTION_LOW 1차 — 캔들 backfill·지수/섹터 cron
 *   - 합 = bucketCapacity=10 (KIS 초당 한도 정확)
 *
 * 역방향 fallback 라우팅 (상위 priority일수록 더 많은 버킷 접근 — 하위 burst가 상위 굶기지 못함):
 *   - USER_PATH  → [USER, SHARED, BG]  (주문은 절대 안 막힘이 최우선 — 3 버킷 모두 소비 가능)
 *   - INGESTION  → [SHARED, BG]        (BG burst로부터 보호 + USER 버킷은 보존)
 *   - BACKFILL / INGESTION_LOW → [BG]  (자기 버킷 소진 시 polling 대기 → timeout, 상위 미침범)
 *
 * 단일 bucket 회귀 (priorityBucketsEnabled=false — ADR 0116 §5-2 rollback):
 *   - 모든 priority가 단일 :global 버킷(capacity=bucketCapacity) 1개만 소비 (Phase 1 안전 최소)
 *
 * Circuit Breaker LOCK:
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회)
 *   - 역방향 fallback으로 비주문 burst가 주문(USER_PATH)을 굶기는 EGW00201 직격 risk 차단 (14:59 evidence)
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 `tryConsume(1)` non-blocking + polling 패턴 — BlockingBucket 미지원 distributed proxy 회피
 *   - 🔄 Bucket4j 8.x ProxyManager Lettuce backend 채택 (Redisson 의존 0 — bundle bloat 회피)
 *   - ✨ 통계 카운터는 AtomicLong (Phase 3 Resilience4j Circuit Breaker 합류 시 위임)
 *   - ✨ 버킷 리스트 순회로 일반화 — `when` exhaustive priority→버킷 매핑 + 2단 reserve를 N단 fallback으로 확장
 */
class RedisKisRateLimiterAdapter(
    private val proxyManager: ProxyManager<String>,
    private val props: KisRateLimitProperties,
    /**
     * SystemEvent emit port (진단 PR #639 P0 hotfix — KIS_RATELIMIT P3 catalog → P1 활성).
     *
     * nullable LOCK 사유 — Bean wiring 단계 분리 + persistence-shared 의존 없는 단위 테스트 허용.
     * production 환경에서는 [KisRateLimiterConfiguration]가 Bean 주입 의무.
     */
    private val systemEventLogger: SystemEventLogger? = null,
) : KisRateLimiterPort {
    private val log = LoggerFactory.getLogger(RedisKisRateLimiterAdapter::class.java)

    /** Phase 1 통계 카운터 (Phase 3 Circuit Breaker에서 권위 위임) */
    private val tpsExceededCount = AtomicLong(0L)
    private val successCount = AtomicLong(0L)

    /**
     * 최근 SystemEvent emit 시각 — spam 회피 aggregator (5분당 1회 emit LOCK).
     *
     * 진보 페르소나 (ADR 0087) 결정:
     *   - 💡 **AtomicReference<Instant>** — lock-free CAS 갱신 (multi-thread acquire 호출 안전)
     *   - ✨ aggregator pattern — 사용자 알림 spam 차단 + DB 부하 회피. EGW00201 burst 시 5분당 1건만 emit
     *   - 🔄 매 호출 emit 옵션은 P3 plan ratelimit-event spec 후속 검토 (현 hotfix scope P0 안정 LOCK)
     */
    private val lastEmittedAt = AtomicReference<Instant>(Instant.EPOCH)

    private companion object {
        /** SystemEvent emit aggregator 임계 — 5분당 최대 1회 emit (사용자 알림 spam 회피) */
        val EMIT_AGGREGATOR_WINDOW: Duration = Duration.ofMinutes(5)

        const val ACTOR = "RedisKisRateLimiterAdapter"
        const val ACTION_TPS_EXCEEDED = "KIS_TPS_EXCEEDED"

        /** priority별 버킷 key suffix (base bucketKey 와 동일 prefix + suffix · ADR 0116 §2-B Redis 3 key). */
        const val USER_KEY_SUFFIX = ":user"
        const val SHARED_KEY_SUFFIX = ":shared"
        const val BG_KEY_SUFFIX = ":bg"
    }

    /**
     * priority별 버킷 capacity 합 = bucketCapacity 인지 (ADR 0116 §4 ENV 합 검증).
     * 불일치 시 부팅 시 WARN + 단일 bucket fallback (fail-safe).
     */
    private val bucketSumValid: Boolean =
        (props.bucketUser + props.bucketShared + props.bucketBg) == props.bucketCapacity

    /**
     * 3-tier priority 버킷 활성 여부.
     *   - priorityBucketsEnabled=true + 합 검증 통과 + 각 버킷 > 0 → 3-tier 라우팅
     *   - 그 외 → 단일 :global 버킷(capacity=bucketCapacity) 회귀 (Phase 1 안전 최소 — rollback)
     */
    private val priorityBucketsActive: Boolean =
        props.priorityBucketsEnabled &&
            bucketSumValid &&
            props.bucketUser > 0L &&
            props.bucketShared > 0L &&
            props.bucketBg > 0L

    init {
        if (props.priorityBucketsEnabled && !bucketSumValid) {
            log.warn(
                "[KisRateLimit] priority 버킷 합 불일치 — 단일 bucket fallback (fail-safe): user={} shared={} bg={} sum={} != capacity={}",
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

    /** USER 버킷 (capacity=bucketUser) — USER_PATH 1차. lazy 초기화 (ProxyManager Bean 부팅 후 가용). */
    private val userBucket: BucketProxy by lazy { buildBucket(USER_KEY_SUFFIX, props.bucketUser) }

    /** SHARED 버킷 (capacity=bucketShared) — INGESTION 1차 + USER_PATH fallback 2단. */
    private val sharedBucket: BucketProxy by lazy { buildBucket(SHARED_KEY_SUFFIX, props.bucketShared) }

    /** BG 버킷 (capacity=bucketBg) — BACKFILL·INGESTION_LOW 1차 + 상위 priority fallback 말단. */
    private val bgBucket: BucketProxy by lazy { buildBucket(BG_KEY_SUFFIX, props.bucketBg) }

    /** 단일 :global 버킷 (capacity=bucketCapacity) — priorityBucketsActive=false 회귀 path (Phase 1). */
    private val globalBucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey) { bucketConfigFor(props.bucketCapacity, props.bucketCapacity) }
    }

    /**
     * priority → 접근 가능 버킷 리스트 (역방향 fallback · ADR 0116 §2-B).
     *
     * 상위 priority일수록 더 많은 버킷 접근 (when exhaustive — 신규 priority 추가 시 빌드 게이트):
     *   - USER_PATH  → [USER, SHARED, BG]  (주문 절대 안 막힘)
     *   - INGESTION  → [SHARED, BG]
     *   - BACKFILL / INGESTION_LOW → [BG]  (상위 미침범)
     *
     * 진보 페르소나 (ADR 0087): 💡 버킷 리스트 순회로 2단 reserve를 N단 fallback으로 일반화.
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
     * 라우팅 알고리즘 (ADR 0116 §2-B):
     *   1. priority별 접근 가능 버킷 리스트를 순회하며 `tryConsume(1)` → 첫 성공 즉시 return
     *   2. 모든 접근 가능 버킷 소진 → polling interval(50ms) sleep 후 재시도
     *   3. timeoutMillis 누적 초과 → throw [KisRateLimitTimeoutException]
     *
     * 역방향 fallback으로 하위 priority(BACKFILL·INGESTION_LOW)는 BG만 접근 → 상위(USER_PATH·INGESTION) 버킷 보존.
     *
     * Phase 3 후속 최적화 후보:
     *   - BucketProxy `asScheduler().tryConsume(1, maxWait, scheduler)` 합류 (ScheduledExecutorService 의존)
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
                    "[KisRateLimit] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={} priorityBucketsActive={}",
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
                // 현 platform thread 환경에서 Thread.sleep은 안전하나, JDK 21 virtual thread 합류 시
                // pinning risk 회피 위해 java.util.concurrent.locks.LockSupport.parkNanos(nanos) 또는
                // Bucket4j의 BucketProxy.asScheduler() (CompletableFuture 기반 non-blocking) 전환 의무.
                Thread.sleep(sleepMillis)
            }
        }
    }

    /**
     * priority 라우팅 단발 소비 (non-blocking) — 접근 가능 버킷 리스트 순회 (ADR 0116 §2-B).
     *
     * - priorityBucketsActive=true: [bucketsFor] 순회하며 첫 성공 버킷에서 소비 (역방향 fallback).
     * - priorityBucketsActive=false: 단일 :global 버킷만 소비 (Phase 1 회귀).
     *
     * 진보 페르소나 (ADR 0087): 💡 `when` exhaustive 버킷 매핑 + `any` 단축평가 순회로 N단 fallback 표현.
     */
    private fun tryConsumeRouted(priority: KisCallPriority): Boolean {
        if (!priorityBucketsActive) return globalBucket.tryConsume(1L)
        return bucketsFor(priority).any { it.tryConsume(1L) }
    }

    /**
     * EGW00201 / HTTP 429 응답 시그널 — 통계 누적 + SystemEvent emit (P0 hotfix).
     *
     * ### 진단 PR #639 P0 hotfix
     * - 기존: AtomicLong + WARN log만 → 사용자 알림센터 0건
     * - 변경: SystemEventLogger.emit(KIS_RATELIMIT, WARN) 합류 — `/system` 페이지 가시화
     * - aggregator 5분 LOCK — spam 회피 (EGW00201 burst 시 1건만 발사)
     *
     * Phase 3 합류 시 Resilience4j Circuit Breaker `onError()` 위임 → 5분 윈도 50% 임계 도달 시 OPEN.
     */
    override fun onTpsExceeded() {
        val count = tpsExceededCount.incrementAndGet()
        log.warn(
            "[KisRateLimit] TPS exceeded signal — count={} (Phase 3 Circuit Breaker 합류 대기)",
            count,
        )
        emitSystemEventIfThresholdReached(count)
    }

    /**
     * SystemEvent emit aggregator — 5분 윈도 1회 LOCK (spam 회피).
     *
     * 알고리즘 (lock-free CAS):
     *   1. 직전 emit 시각과 현재 시각 비교
     *   2. 5분 미경과 → skip (log debug)
     *   3. 5분 경과 → AtomicReference CAS 갱신 + emit
     *   4. CAS 실패 (다른 thread가 동시에 갱신) → skip (단일 emit 보장)
     */
    private fun emitSystemEventIfThresholdReached(currentCount: Long) {
        val logger = systemEventLogger ?: return
        val now = Instant.now()
        val prev = lastEmittedAt.get()
        if (Duration.between(prev, now) < EMIT_AGGREGATOR_WINDOW) {
            return // aggregator skip
        }
        if (!lastEmittedAt.compareAndSet(prev, now)) {
            return // race — 다른 thread 가 emit (단일 보장)
        }

        runCatching {
            logger.emit(
                SystemEvent(
                    category = SystemEventCategory.KIS_RATELIMIT,
                    severity = SystemEventSeverity.WARN,
                    actor = ACTOR,
                    action = ACTION_TPS_EXCEEDED,
                    userId = null, // system-wide broadcast (Bucket4j는 단일 글로벌 bucket)
                    correlationId = null,
                    detailJson = """{"tpsExceededCount":$currentCount,"bucketKey":"${props.bucketKey}","aggregatorWindowMinutes":5}""",
                ),
            )
            log.warn(
                "[KisRateLimit] SystemEvent KIS_RATELIMIT emit — count={} window=5min",
                currentCount,
            )
        }.onFailure { e ->
            log.error(
                "[KisRateLimit] SystemEvent emit 실패 — count={} err={}",
                currentCount,
                e.message,
            )
        }
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

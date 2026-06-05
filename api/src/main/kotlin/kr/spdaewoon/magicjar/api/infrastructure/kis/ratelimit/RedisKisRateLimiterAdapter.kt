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
 * priority 차등 라우팅 (ADR 0112 의제 — 2026-06-05 주문 path 보호):
 *   - shared bucket (capacity = bucketCapacity - userPathReserveTokens): 모든 priority 1차 소비
 *   - reserve bucket (capacity = userPathReserveTokens): USER_PATH 전용 fallback (shared 소진 시)
 *   - 비주문(INGESTION·BACKFILL·INGESTION_LOW)은 shared 만 소비 → 주문(USER_PATH) reserve 굶지 않음
 *   - 두 bucket 합 = bucketCapacity (KIS 초당 한도 절대 미초과)
 *   - userPathReserveTokens=0 시 단일 bucket 회귀 (reserve 비활성 — rollback path)
 *
 * Circuit Breaker LOCK:
 *   - Circuit Breaker OPEN 차단 미구현 (Phase 3 후속)
 *
 * 자본 영향:
 *   - 본 adapter 발행만으로 직접 자본 영향 0 (caller adapter가 `acquire()` 미호출 시 우회)
 *   - reserve 라우팅으로 비주문 burst 가 주문 path 를 굶기는 EGW00201 직격 risk 차단 (14:59 evidence)
 *
 * 진보 페르소나 결정 메모 (ADR 0087):
 *   - 💡 `tryConsume(1)` non-blocking + polling 패턴 — BlockingBucket 미지원 distributed proxy 회피
 *   - 🔄 Bucket4j 8.x ProxyManager Lettuce backend 채택 (Redisson 의존 0 — bundle bloat 회피)
 *   - ✨ 통계 카운터는 AtomicLong (Phase 3 Resilience4j Circuit Breaker 합류 시 위임)
 *   - ✨ 2-bucket reserve 모델 — capacity 증설 0 (KIS 한도 분할), 차등 라우팅을 `tryConsume` 2단 fallback 으로 표현
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

        /** USER_PATH 전용 reserve bucket key suffix (shared bucketKey 와 동일 prefix + ":user-reserve"). */
        const val RESERVE_KEY_SUFFIX = ":user-reserve"
    }

    /**
     * reserve 토큰 수 — bucketCapacity 로 clamp (음수·초과 방지).
     *   - 0 ≤ reserve ≤ bucketCapacity
     *   - 0 이면 차등 라우팅 비활성 (단일 bucket 회귀 — rollback path)
     */
    private val reserveTokens: Long = props.userPathReserveTokens.coerceIn(0L, props.bucketCapacity)

    /** shared pool capacity = bucketCapacity - reserve (비주문·USER_PATH 1차 공용). */
    private val sharedCapacity: Long = props.bucketCapacity - reserveTokens

    /** reserve 라우팅 활성 여부 (reserve > 0 이고 shared > 0 일 때만 의미 — 둘 중 하나 0 이면 단일 bucket 동작). */
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
     * shared bucket — 모든 priority 1차 소비.
     *   - reserve 활성 시 capacity = bucketCapacity - reserve
     *   - reserve 비활성(reserveTokens=0) 시 capacity = bucketCapacity (기존 단일 bucket 동작 그대로)
     *
     * Redis backend Lettuce ProxyManager 가 multi-JVM share 보장. lazy 초기화 (ProxyManager Bean 부팅 후 가용).
     */
    private val sharedBucket: BucketProxy by lazy {
        val cap = if (reserveEnabled) sharedCapacity else props.bucketCapacity
        proxyManager.builder().build(props.bucketKey) { bucketConfigFor(cap, cap) }
    }

    /**
     * reserve bucket — USER_PATH 전용 fallback (shared 소진 시).
     *   - capacity = reserveTokens, 동일 1s interval refill
     *   - reserve 비활성 시 미발행 (lazy — 접근 0)
     */
    private val reserveBucket: BucketProxy by lazy {
        proxyManager.builder().build(props.bucketKey + RESERVE_KEY_SUFFIX) {
            bucketConfigFor(reserveTokens, reserveTokens)
        }
    }

    /**
     * KIS REST 호출 직전 token 1개 acquire (blocking polling).
     *
     * 차등 라우팅 알고리즘 (ADR 0112 의제):
     *   1. shared bucket `tryConsume(1)` 시도 → 성공 시 즉시 return
     *   2. (USER_PATH + reserve 활성) shared 실패 → reserve bucket `tryConsume(1)` → 성공 시 return
     *   3. 둘 다 실패 → polling interval(50ms) sleep 후 재시도
     *   4. timeoutMillis 누적 초과 → throw [KisRateLimitTimeoutException]
     *
     * 비주문(INGESTION·BACKFILL·INGESTION_LOW)은 reserve 접근 0 → shared 소진 시 reserve 가 USER_PATH 를 위해 보존됨.
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
                    "[KisRateLimit] acquire TIMEOUT — priority={} timeoutMs={} attempts={} bucketKey={} reserveEnabled={}",
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
     *
     * 진보 페르소나 (ADR 0087): 💡 USER_PATH 만 reserve 접근 — when 분기 exhaustive 로 신규 priority 추가 시 기본 shared-only.
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

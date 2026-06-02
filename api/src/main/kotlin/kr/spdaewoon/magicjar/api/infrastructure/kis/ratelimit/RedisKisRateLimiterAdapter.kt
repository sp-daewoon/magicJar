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
    }

    /**
     * Bucket4j BucketConfiguration — interval refill (KIS 서버의 초당 윈도우에 맞춰 burst 완화).
     *
     * Phase 1 LOCK: 10 token / 1 sec interval.
     *   - greedy refill은 초기 burst 뒤 100ms마다 토큰을 보충해 KIS가 같은 초 안에 11건 이상으로 볼 수 있음.
     *   - interval refill은 한 윈도우를 소진하면 다음 refill까지 대기해 EGW00201 빈도를 낮춘다.
     */
    private val bucketConfiguration: BucketConfiguration =
        BucketConfiguration
            .builder()
            .addLimit { limit ->
                limit
                    .capacity(props.bucketCapacity)
                    .refillIntervally(
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
                // TODO: virtual thread 도입 시 LockSupport.parkNanos 또는 BucketProxy.asScheduler() 합류 — ADR 0037 Phase 3 후속
                // 현 platform thread 환경에서 Thread.sleep은 안전하나, JDK 21 virtual thread 합류 시
                // pinning risk 회피 위해 java.util.concurrent.locks.LockSupport.parkNanos(nanos) 또는
                // Bucket4j의 BucketProxy.asScheduler() (CompletableFuture 기반 non-blocking) 전환 의무.
                Thread.sleep(sleepMillis)
            }
        }
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

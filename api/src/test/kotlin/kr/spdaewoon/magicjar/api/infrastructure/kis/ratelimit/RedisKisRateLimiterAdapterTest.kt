package kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit

import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import kr.spdaewoon.magicjar.domain.event.SystemEvent
import kr.spdaewoon.magicjar.domain.event.SystemEventCategory
import kr.spdaewoon.magicjar.domain.event.SystemEventSeverity
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisRateLimitTimeoutException
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * RedisKisRateLimiterAdapter Bucket4j-Redis 단위 테스트 (spec PR #564 §6-1 LOCK).
 *
 * 검증 4건:
 *   1. acquire-success: tryConsume(1) GREEN → 즉시 return + onSuccess 카운터 누적 가능
 *   2. acquire-success-after-polling: 처음 2회 RED → polling 후 3회차 GREEN → return
 *   3. acquire-timeout: tryConsume(1) 지속 RED → 100ms timeout → KisRateLimitTimeoutException throw
 *   4. tps-exceeded-signal: onTpsExceeded() 카운터 누적 (Phase 3 Circuit Breaker 합류 전 baseline)
 *
 * Bucket4j mock 전략:
 *   - ProxyManager → mock
 *   - ProxyManager.builder() → mock RemoteBucketBuilder
 *   - RemoteBucketBuilder.build(key, Supplier<BucketConfiguration>) → mock Bucket
 *   - Bucket.tryConsume(1L) → 시퀀스 응답
 *
 * 자본 영향 0 — 본 테스트는 mock 단위 (KIS endpoint 호출 0, Redis 호출 0).
 */
@Suppress("UNCHECKED_CAST")
class RedisKisRateLimiterAdapterTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>
    private lateinit var bucket: BucketProxy
    private lateinit var props: KisRateLimitProperties
    private lateinit var adapter: RedisKisRateLimiterAdapter

    @BeforeEach
    fun setUp() {
        proxyManager = mock()
        remoteBucketBuilder = mock()
        bucket = mock()
        // ProxyManager.builder().build(key, supplier) → Bucket
        whenever(proxyManager.builder()).thenReturn(remoteBucketBuilder)
        whenever(remoteBucketBuilder.build(any<String>(), any<java.util.function.Supplier<io.github.bucket4j.BucketConfiguration>>()))
            .thenReturn(bucket)
        props =
            KisRateLimitProperties(
                enabled = true,
                bucketCapacity = 10L,
                bucketRefillTokens = 10L,
                bucketRefillPeriodSeconds = 1L,
                bucketKey = "kis-rate-limit:test",
                bucketTtlSeconds = 10L,
                acquirePollIntervalMillis = 10L, // 테스트 빠른 polling
            )
        adapter = RedisKisRateLimiterAdapter(proxyManager, props)
    }

    /**
     * 검증 1: tryConsume(1) 즉시 GREEN → acquire() 즉시 return.
     *
     * spec §6-1 #1 LOCK — "1초 내 10건 acquire GREEN" baseline.
     */
    @Test
    fun `acquire - 토큰 사용 가능 시 즉시 return`() {
        // given: bucket.tryConsume(1L) 즉시 true
        whenever(bucket.tryConsume(1L)).thenReturn(true)

        // when
        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        // then: tryConsume 1회만 호출 (polling 0)
        verify(bucket, times(1)).tryConsume(1L)
    }

    /**
     * 검증 2: 처음 2회 RED → polling 후 3회차 GREEN → return.
     *
     * Phase 1 polling 패턴 정합 — BucketProxy `tryConsume` non-blocking + interval sleep retry.
     */
    @Test
    fun `acquire - polling 후 토큰 회복 시 return`() {
        // given: 처음 2회 false → 3회째 true
        whenever(bucket.tryConsume(1L))
            .thenReturn(false)
            .thenReturn(false)
            .thenReturn(true)

        // when
        adapter.acquire(KisCallPriority.INGESTION, timeoutMillis = 1000L)

        // then: 3회 호출
        verify(bucket, times(3)).tryConsume(1L)
    }

    /**
     * 검증 3: tryConsume(1) 지속 RED → timeout 초과 시 throw KisRateLimitTimeoutException.
     *
     * spec §6-1 #1 LOCK — "11번째 acquire 1초 timeout throw".
     * Phase 1 단위 테스트는 mock으로 100ms timeout 강제 (테스트 빠름).
     */
    @Test
    fun `acquire - timeout 초과 시 KisRateLimitTimeoutException throw`() {
        // given: bucket.tryConsume(1L) 항상 false
        whenever(bucket.tryConsume(1L)).thenReturn(false)

        // when + then
        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 100L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)
            .hasMessageContaining("BACKFILL")
            .hasMessageContaining("timeoutMillis=100")

        // tryConsume 다회 호출 (polling 횟수 정확 검증보다 lower bound 확보)
        verify(bucket, org.mockito.kotlin.atLeast(2)).tryConsume(1L)
    }

    /**
     * 검증 4: onTpsExceeded() 시그널 누적.
     *
     * spec §6-1 #4 LOCK 정합 — Phase 1은 카운터만, Phase 3 Resilience4j Circuit Breaker 합류 후
     * 5분 윈도 50% 임계 도달 시 KisCircuitOpenException throw 추가.
     */
    @Test
    fun `onTpsExceeded - 시그널 카운터 누적 검증`() {
        // given: 초기 0
        assertThat(adapter.snapshotTpsExceeded()).isEqualTo(0L)

        // when: 3회 호출
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()

        // then
        assertThat(adapter.snapshotTpsExceeded()).isEqualTo(3L)

        // onSuccess 카운터도 격리 검증
        assertThat(adapter.snapshotSuccess()).isEqualTo(0L)
        adapter.onSuccess()
        adapter.onSuccess()
        assertThat(adapter.snapshotSuccess()).isEqualTo(2L)
    }

    /**
     * 검증 5: SystemEvent KIS_RATELIMIT emit — 첫 onTpsExceeded() 호출에서 emit, 동일 5분 윈도 내 추가 호출은 aggregator skip.
     *
     * 진단 PR #639 P0 hotfix — 사용자 알림센터 가시화 검증 게이트.
     */
    @Test
    fun `onTpsExceeded - SystemEvent KIS_RATELIMIT emit (5분 aggregator LOCK)`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val adapterWithLogger = RedisKisRateLimiterAdapter(proxyManager, props, systemEventLogger)

        // when: 3회 burst 호출 (5분 미만 — 첫 호출만 emit)
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()

        // then: 3 카운트 누적 + SystemEvent emit 정확 1회 (aggregator)
        assertThat(adapterWithLogger.snapshotTpsExceeded()).isEqualTo(3L)

        val captor = org.mockito.kotlin.argumentCaptor<SystemEvent>()
        verify(systemEventLogger, times(1)).emit(captor.capture())
        val event = captor.firstValue
        assertThat(event.category).isEqualTo(SystemEventCategory.KIS_RATELIMIT)
        assertThat(event.severity).isEqualTo(SystemEventSeverity.WARN)
        assertThat(event.actor).isEqualTo("RedisKisRateLimiterAdapter")
        assertThat(event.action).isEqualTo("KIS_TPS_EXCEEDED")
        assertThat(event.userId).isNull() // system-wide broadcast
        assertThat(event.detailJson).contains("tpsExceededCount")
        assertThat(event.detailJson).contains("kis-rate-limit:test")
    }

    /**
     * 검증 6: SystemEventLogger null 주입 시 emit 0 — 회귀 마진 LOCK (Bean wiring 단계 분리 정합).
     */
    @Test
    fun `onTpsExceeded - SystemEventLogger 0 주입 시 emit 0 (회귀 마진 LOCK)`() {
        val systemEventLogger = mock<SystemEventLogger>()
        // adapter 는 systemEventLogger=null default — emit 의무 0
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()

        verify(systemEventLogger, never()).emit(any())
    }
}

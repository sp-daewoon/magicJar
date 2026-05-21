package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

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
import org.mockito.kotlin.argumentCaptor
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * ConsumerRedisKisRateLimiterAdapter Bucket4j-Redis 단위 테스트 (spec PR #564 §6-1 LOCK · ADR 0037 정합).
 *
 * api 모듈 [kr.spdaewoon.magicjar.api.infrastructure.kis.ratelimit.RedisKisRateLimiterAdapter]와 동일 패턴.
 * spec §2-3 옵션 a 정합 — consumer 모듈 자체 Bean wiring 단위 검증.
 *
 * 자본 영향 0 — 본 테스트는 mock 단위 (KIS endpoint 호출 0, Redis 호출 0).
 */
@Suppress("UNCHECKED_CAST")
class ConsumerRedisKisRateLimiterAdapterTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>
    private lateinit var bucket: BucketProxy
    private lateinit var props: ConsumerKisRateLimitProperties
    private lateinit var adapter: ConsumerRedisKisRateLimiterAdapter

    @BeforeEach
    fun setUp() {
        proxyManager = mock()
        remoteBucketBuilder = mock()
        bucket = mock()
        whenever(proxyManager.builder()).thenReturn(remoteBucketBuilder)
        whenever(
            remoteBucketBuilder.build(
                any<String>(),
                any<java.util.function.Supplier<io.github.bucket4j.BucketConfiguration>>(),
            ),
        ).thenReturn(bucket)
        props =
            ConsumerKisRateLimitProperties(
                enabled = true,
                bucketCapacity = 10L,
                bucketRefillTokens = 10L,
                bucketRefillPeriodSeconds = 1L,
                bucketKey = "kis-rate-limit:test-consumer",
                bucketTtlSeconds = 10L,
                acquirePollIntervalMillis = 10L,
            )
        adapter = ConsumerRedisKisRateLimiterAdapter(proxyManager, props)
    }

    @Test
    fun `acquire - 토큰 사용 가능 시 즉시 return`() {
        whenever(bucket.tryConsume(1L)).thenReturn(true)
        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
        verify(bucket, times(1)).tryConsume(1L)
    }

    @Test
    fun `acquire - polling 후 토큰 회복 시 return`() {
        whenever(bucket.tryConsume(1L))
            .thenReturn(false)
            .thenReturn(false)
            .thenReturn(true)
        adapter.acquire(KisCallPriority.INGESTION, timeoutMillis = 1000L)
        verify(bucket, times(3)).tryConsume(1L)
    }

    @Test
    fun `acquire - timeout 초과 시 KisRateLimitTimeoutException throw`() {
        whenever(bucket.tryConsume(1L)).thenReturn(false)
        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 100L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)
            .hasMessageContaining("BACKFILL")
            .hasMessageContaining("timeoutMillis=100")
        verify(bucket, org.mockito.kotlin.atLeast(2)).tryConsume(1L)
    }

    @Test
    fun `onTpsExceeded - 시그널 카운터 누적 검증`() {
        assertThat(adapter.snapshotTpsExceeded()).isEqualTo(0L)
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()
        adapter.onTpsExceeded()
        assertThat(adapter.snapshotTpsExceeded()).isEqualTo(3L)

        assertThat(adapter.snapshotSuccess()).isEqualTo(0L)
        adapter.onSuccess()
        adapter.onSuccess()
        assertThat(adapter.snapshotSuccess()).isEqualTo(2L)
    }

    /**
     * 진단 PR #639 P0 hotfix — SystemEvent KIS_RATELIMIT emit aggregator 검증.
     * 첫 호출만 emit, 5분 미만 추가 호출은 skip (사용자 알림 spam 회피).
     */
    @Test
    fun `onTpsExceeded - SystemEvent KIS_RATELIMIT emit (5분 aggregator LOCK)`() {
        val systemEventLogger = mock<SystemEventLogger>()
        val adapterWithLogger = ConsumerRedisKisRateLimiterAdapter(proxyManager, props, systemEventLogger)

        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()
        adapterWithLogger.onTpsExceeded()

        assertThat(adapterWithLogger.snapshotTpsExceeded()).isEqualTo(3L)
        val captor = argumentCaptor<SystemEvent>()
        verify(systemEventLogger, times(1)).emit(captor.capture())
        val event = captor.firstValue
        assertThat(event.category).isEqualTo(SystemEventCategory.KIS_RATELIMIT)
        assertThat(event.severity).isEqualTo(SystemEventSeverity.WARN)
        assertThat(event.actor).isEqualTo("ConsumerRedisKisRateLimiterAdapter")
        assertThat(event.action).isEqualTo("KIS_TPS_EXCEEDED")
        assertThat(event.detailJson).contains("\"module\":\"consumer\"")
    }
}

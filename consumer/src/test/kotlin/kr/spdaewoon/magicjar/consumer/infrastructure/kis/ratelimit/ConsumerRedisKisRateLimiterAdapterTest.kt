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

    /** shared pool bucket — bucketKey 로 발행 (모든 priority 1차 소비). */
    private lateinit var bucket: BucketProxy

    /** USER_PATH 전용 reserve bucket — bucketKey + ":user-reserve" 로 발행. */
    private lateinit var reserveBucket: BucketProxy
    private lateinit var props: ConsumerKisRateLimitProperties
    private lateinit var adapter: ConsumerRedisKisRateLimiterAdapter

    private companion object {
        const val BUCKET_KEY = "kis-rate-limit:test-consumer"
        const val RESERVE_KEY = "$BUCKET_KEY:user-reserve"
    }

    @BeforeEach
    fun setUp() {
        proxyManager = mock()
        remoteBucketBuilder = mock()
        bucket = mock()
        reserveBucket = mock()
        whenever(proxyManager.builder()).thenReturn(remoteBucketBuilder)
        // key 분기 — shared(bucketKey) vs reserve(bucketKey:user-reserve) 별도 mock.
        whenever(
            remoteBucketBuilder.build(
                org.mockito.kotlin.eq(BUCKET_KEY),
                any<java.util.function.Supplier<io.github.bucket4j.BucketConfiguration>>(),
            ),
        ).thenReturn(bucket)
        whenever(
            remoteBucketBuilder.build(
                org.mockito.kotlin.eq(RESERVE_KEY),
                any<java.util.function.Supplier<io.github.bucket4j.BucketConfiguration>>(),
            ),
        ).thenReturn(reserveBucket)
        props =
            ConsumerKisRateLimitProperties(
                enabled = true,
                bucketCapacity = 10L,
                bucketRefillTokens = 10L,
                bucketKey = BUCKET_KEY,
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
        whenever(reserveBucket.tryConsume(1L)).thenReturn(false)
        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 100L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)
            .hasMessageContaining("BACKFILL")
            .hasMessageContaining("timeoutMillis=100")
        verify(bucket, org.mockito.kotlin.atLeast(2)).tryConsume(1L)
        // BACKFILL 은 reserve 미접근 — 비주문 path 가 USER_PATH reserve 를 소비하지 않음 보장
        verify(reserveBucket, org.mockito.kotlin.never()).tryConsume(1L)
    }

    /**
     * ★ 차등 라우팅 회귀 (ADR 0112 의제, 14:59 evidence 직격):
     * shared pool 이 비주문 트래픽으로 완전 소진(tryConsume RED)되어도 USER_PATH 주문은 reserve 로 통과.
     */
    @Test
    fun `acquire - shared 소진 시 USER_PATH 는 reserve fallback 으로 통과`() {
        whenever(bucket.tryConsume(1L)).thenReturn(false) // shared 완전 소진 (비주문 burst 가정)
        whenever(reserveBucket.tryConsume(1L)).thenReturn(true) // USER_PATH 전용 reserve 잔여

        adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(bucket, times(1)).tryConsume(1L) // shared 1차 시도
        verify(reserveBucket, times(1)).tryConsume(1L) // reserve fallback 1회로 통과
    }

    /**
     * ★ 비주문 path 가 reserve 를 굶기지 않음 (역방향 회귀):
     * shared 소진 시 INGESTION 은 reserve 미접근 → timeout (reserve 보존).
     */
    @Test
    fun `acquire - shared 소진 시 비주문(INGESTION)은 reserve 미접근 timeout`() {
        whenever(bucket.tryConsume(1L)).thenReturn(false)
        whenever(reserveBucket.tryConsume(1L)).thenReturn(true) // reserve 에 토큰 있어도

        assertThatThrownBy {
            adapter.acquire(KisCallPriority.INGESTION, timeoutMillis = 100L)
        }.isInstanceOf(KisRateLimitTimeoutException::class.java)

        verify(reserveBucket, org.mockito.kotlin.never()).tryConsume(1L) // INGESTION 은 reserve 접근 0
    }

    /**
     * reserve 비활성(userPathReserveTokens=0) 시 단일 bucket 회귀 — rollback path 회귀.
     */
    @Test
    fun `acquire - reserve 0 설정 시 USER_PATH 도 reserve 미발행 (단일 bucket 회귀)`() {
        val singleProps = props.copy(userPathReserveTokens = 0L)
        val singleAdapter = ConsumerRedisKisRateLimiterAdapter(proxyManager, singleProps)
        whenever(bucket.tryConsume(1L)).thenReturn(true)

        singleAdapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)

        verify(bucket, times(1)).tryConsume(1L)
        verify(reserveBucket, org.mockito.kotlin.never()).tryConsume(1L)
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

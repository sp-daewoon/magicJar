package kr.spdaewoon.magicjar.consumer.infrastructure.kis.ratelimit

import io.github.bucket4j.distributed.BucketProxy
import io.github.bucket4j.distributed.proxy.ProxyManager
import io.github.bucket4j.distributed.proxy.RemoteBucketBuilder
import io.lettuce.core.api.StatefulRedisConnection
import io.lettuce.core.api.sync.RedisCommands
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCallPriority
import kr.spdaewoon.magicjar.domain.market.ratelimit.KisCircuitOpenException
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * ConsumerRedisKisRateLimiterAdapter Circuit Breaker 어댑터 단위 테스트 (ADR 0037 Phase 3 · 정책 C).
 *
 * domain 순수함수([kr.spdaewoon.magicjar.domain.market.ratelimit.KisCircuitBreakerDecision])는 별도 테스트가 커버하고,
 * 여기서는 어댑터가 OPEN 플래그를 읽어 **USER_PATH는 통과·배경 트래픽은 차단**하는 결선을 검증한다.
 *
 * 자본 안전 불변식(정책 C): 회로가 OPEN이어도 USER_PATH(청산·손절 주문)는 절대 차단하지 않는다.
 * 자본 영향 0 — mock 단위 (KIS·Redis 실호출 0).
 */
@Suppress("UNCHECKED_CAST")
class ConsumerKisCircuitBreakerAdapterTest {
    private lateinit var proxyManager: ProxyManager<String>
    private lateinit var remoteBucketBuilder: RemoteBucketBuilder<String>
    private lateinit var userBucket: BucketProxy
    private lateinit var sharedBucket: BucketProxy
    private lateinit var bgBucket: BucketProxy
    private lateinit var globalBucket: BucketProxy
    private lateinit var conn: StatefulRedisConnection<String, ByteArray>
    private lateinit var sync: RedisCommands<String, ByteArray>
    private lateinit var props: ConsumerKisRateLimitProperties

    private companion object {
        const val BUCKET_KEY = "kis-rate-limit:test-consumer-cb"
        const val OPEN_KEY = "$BUCKET_KEY:circuit:open"
    }

    @BeforeEach
    fun setUp() {
        proxyManager = mock()
        remoteBucketBuilder = mock()
        userBucket = mock()
        sharedBucket = mock()
        bgBucket = mock()
        globalBucket = mock()
        whenever(proxyManager.builder()).thenReturn(remoteBucketBuilder)
        wireBucket("$BUCKET_KEY:user", userBucket)
        wireBucket("$BUCKET_KEY:shared", sharedBucket)
        wireBucket("$BUCKET_KEY:bg", bgBucket)
        wireBucket(BUCKET_KEY, globalBucket)
        // 토큰은 항상 확보 가능 — 차단은 오직 circuit 때문이어야 함
        whenever(userBucket.tryConsume(1L)).thenReturn(true)
        whenever(sharedBucket.tryConsume(1L)).thenReturn(true)
        whenever(bgBucket.tryConsume(1L)).thenReturn(true)

        conn = mock()
        sync = mock()
        whenever(conn.sync()).thenReturn(sync)

        props =
            ConsumerKisRateLimitProperties(
                enabled = true,
                bucketCapacity = 10L,
                bucketRefillTokens = 10L,
                priorityBucketsEnabled = true,
                bucketUser = 4L,
                bucketShared = 3L,
                bucketBg = 3L,
                bucketKey = BUCKET_KEY,
                bucketTtlSeconds = 10L,
                acquirePollIntervalMillis = 10L,
            )
    }

    private fun wireBucket(
        key: String,
        bucket: BucketProxy,
    ) {
        whenever(
            remoteBucketBuilder.build(
                eq(key),
                any<java.util.function.Supplier<io.github.bucket4j.BucketConfiguration>>(),
            ),
        ).thenReturn(bucket)
    }

    private fun adapterWithConn() = ConsumerRedisKisRateLimiterAdapter(proxyManager, props, null, null, conn)

    @Test
    fun `circuit OPEN - BACKFILL 은 토큰 소비 전 차단`() {
        whenever(sync.get(OPEN_KEY)).thenReturn("1".toByteArray())
        val adapter = adapterWithConn()

        assertThatThrownBy {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)
        }.isInstanceOf(KisCircuitOpenException::class.java)

        verify(bgBucket, never()).tryConsume(1L) // 토큰 소비 전에 차단
    }

    @Test
    fun `circuit OPEN - USER_PATH 는 정책 C 로 통과 (청산·손절 보호)`() {
        whenever(sync.get(OPEN_KEY)).thenReturn("1".toByteArray())
        val adapter = adapterWithConn()

        assertThatCode {
            adapter.acquire(KisCallPriority.USER_PATH, timeoutMillis = 1000L)
        }.doesNotThrowAnyException()

        verify(userBucket, times(1)).tryConsume(1L) // OPEN이어도 정상 토큰 소비
    }

    @Test
    fun `circuit CLOSED - BACKFILL 정상 통과`() {
        whenever(sync.get(OPEN_KEY)).thenReturn(null) // OPEN 플래그 없음 = CLOSED
        val adapter = adapterWithConn()

        assertThatCode {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)
        }.doesNotThrowAnyException()

        verify(bgBucket, times(1)).tryConsume(1L)
    }

    @Test
    fun `circuit connection 미주입 - 항상 CLOSED fail-safe (zero-regression)`() {
        val adapter = ConsumerRedisKisRateLimiterAdapter(proxyManager, props) // conn 없음

        assertThatCode {
            adapter.acquire(KisCallPriority.BACKFILL, timeoutMillis = 1000L)
        }.doesNotThrowAnyException()

        verify(bgBucket, times(1)).tryConsume(1L)
    }
}

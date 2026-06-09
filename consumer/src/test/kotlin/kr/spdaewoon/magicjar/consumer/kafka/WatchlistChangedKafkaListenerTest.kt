package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.ingestion.SubscriptionRouter
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

/**
 * WatchlistChangedKafkaListener smoke test (ADR 0048 §12-1-1 정합).
 * - 테스트 1: ADDED 이벤트 → SubscriptionRouter.add 호출 (cold-start 백필 폐지 — ADR 0119)
 * - 테스트 2: REMOVED 이벤트 → SubscriptionRouter.remove + Redis evict 호출
 */
class WatchlistChangedKafkaListenerTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val subscriptionRouter = mockk<SubscriptionRouter>(relaxed = true)
    private val redisQuoteCacheAdapter = mockk<RedisQuoteCacheAdapter>(relaxed = true)
    private val redisSourceTracker = mockk<RedisSourceTrackerAdapter>(relaxed = true)
    private val ack = mockk<Acknowledgment>(relaxed = true)

    private lateinit var listener: WatchlistChangedKafkaListener

    @BeforeEach
    fun setUp() {
        listener =
            WatchlistChangedKafkaListener(
                objectMapper = objectMapper,
                subscriptionRouter = subscriptionRouter,
                redisQuoteCacheAdapter = redisQuoteCacheAdapter,
                redisSourceTracker = redisSourceTracker,
            )
    }

    @Test
    fun `ADDED 이벤트 수신 시 cold-start 백필 없이 SubscriptionRouter add 호출`() {
        val payload =
            """{"userId":"user001","code":"005930","action":"ADDED","occurredAt":"2026-05-07T10:00:00Z"}"""
        val record = ConsumerRecord("watchlist.changed.v1", 0, 0L, "user001", payload)

        listener.onMessage(record, ack)

        // ADR 0119 — proactive cold-start 백필 폐지: WS/polling 분배만 수행, 과거 1m/일봉 KIS 백필 0.
        verify { subscriptionRouter.add("user001", "005930", 100) }
        verify { ack.acknowledge() }
    }

    @Test
    fun `REMOVED 이벤트 수신 시 SubscriptionRouter remove + Redis evict 호출`() {
        val payload =
            """{"userId":"user001","code":"005930","action":"REMOVED","occurredAt":"2026-05-07T10:01:00Z"}"""
        val record = ConsumerRecord("watchlist.changed.v1", 0, 1L, "user001", payload)

        listener.onMessage(record, ack)

        verify { subscriptionRouter.remove("user001", "005930") }
        verify { redisQuoteCacheAdapter.evict("005930") }
        verify { redisSourceTracker.remove("user001", "005930") }
        verify { ack.acknowledge() }
    }
}

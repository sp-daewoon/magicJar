package kr.spdaewoon.magicjar.consumer.kafka

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisQuoteCacheAdapter
import kr.spdaewoon.magicjar.consumer.infrastructure.cache.RedisSourceTrackerAdapter
import kr.spdaewoon.magicjar.consumer.ingestion.ColdStartExecutor
import kr.spdaewoon.magicjar.consumer.ingestion.SubscriptionRouter
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.support.Acknowledgment

/**
 * WatchlistChangedKafkaListener smoke test (ADR 0048 §12-1-1 정합).
 * - 테스트 1: ADDED 이벤트 → cold-start 트리거 + SubscriptionRouter.add 호출
 * - 테스트 2: REMOVED 이벤트 → SubscriptionRouter.remove + Redis evict 호출
 */
class WatchlistChangedKafkaListenerTest {
    private val objectMapper = ObjectMapper().registerKotlinModule()
    private val subscriptionRouter = mockk<SubscriptionRouter>(relaxed = true)
    private val coldStartExecutor = mockk<ColdStartExecutor>(relaxed = true)
    private val redisQuoteCacheAdapter = mockk<RedisQuoteCacheAdapter>(relaxed = true)
    private val redisSourceTracker = mockk<RedisSourceTrackerAdapter>(relaxed = true)
    private val candleRepository = mockk<CandleRepository>()
    private val ack = mockk<Acknowledgment>(relaxed = true)

    private lateinit var listener: WatchlistChangedKafkaListener

    @BeforeEach
    fun setUp() {
        listener =
            WatchlistChangedKafkaListener(
                objectMapper = objectMapper,
                subscriptionRouter = subscriptionRouter,
                coldStartExecutor = coldStartExecutor,
                redisQuoteCacheAdapter = redisQuoteCacheAdapter,
                redisSourceTracker = redisSourceTracker,
                candleRepository = candleRepository,
            )
    }

    @Test
    fun `ADDED 이벤트 수신 시 cold-start 트리거 + SubscriptionRouter add 호출`() {
        val payload =
            """{"userId":"user001","code":"005930","action":"ADDED","occurredAt":"2026-05-07T10:00:00Z"}"""
        val record = ConsumerRecord("watchlist.changed.v1", 0, 0L, "user001", payload)

        // candle 미존재 → cold-start 필요
        every {
            candleRepository.countByCodeAndIntervalIn("005930", any())
        } returns 0L

        listener.onMessage(record, ack)

        verify { coldStartExecutor.submit("005930") }
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

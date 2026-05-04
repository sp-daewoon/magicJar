package com.mj.consumer.messaging

import com.mj.consumer.infrastructure.cache.ConsumerRedisUserCandleCacheAdapter
import com.mj.consumer.messaging.listener.UserCandleListener
import com.mj.domain.market.event.CandleSnapshot
import com.mj.domain.market.event.UserCandleUpdatedEvent
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.ImportAutoConfiguration
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.annotation.EnableKafka
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * UserCandleListener EmbeddedKafka 왕복 테스트 — ADR 0050 R3-4.
 *
 * publish: KafkaTemplate → market.candle.user.v1
 * receive: UserCandleListener.onUserCandleUpdated → ConsumerRedisUserCandleCacheAdapter.upsertAll
 *
 * 외부 의존성(DB·Redis·KIS) 0 — Redis 어댑터는 mockk으로 대체.
 */
@SpringBootTest(
    classes = [UserCandleRoundtripIT.TestConfig::class],
)
@EmbeddedKafka(
    partitions = 1,
    topics = ["market.candle.user.v1"],
    brokerProperties = ["listeners=PLAINTEXT://localhost:0", "port=0"],
)
@TestPropertySource(
    properties = [
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=user-candle-rt-test",
        "magicjar.kafka.topics.userCandleUpdated=market.candle.user.v1",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=*",
    ],
)
class UserCandleRoundtripIT {
    @Configuration
    @EnableKafka
    @ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
    class TestConfig {
        val cacheAdapterMock: ConsumerRedisUserCandleCacheAdapter = mockk(relaxed = true)
        val receivedEvents: MutableList<UserCandleUpdatedEvent> = CopyOnWriteArrayList()

        @Bean
        fun consumerRedisUserCandleCacheAdapter(): ConsumerRedisUserCandleCacheAdapter = cacheAdapterMock

        @Bean
        fun userCandleListener(adapter: ConsumerRedisUserCandleCacheAdapter): UserCandleListener {
            // listener가 실제로 호출됐는지 추적하기 위해 upsertAll 호출 시 receivedEvents 기록
            every {
                adapter.upsertAll(any(), any(), any(), any())
            } answers {
                val event =
                    UserCandleUpdatedEvent(
                        userId = firstArg(),
                        code = secondArg(),
                        interval = thirdArg(),
                        candles = emptyList(), // tracking 전용 — 실제 값은 verify로 확인
                        occurredAt = Instant.now(),
                    )
                receivedEvents.add(event)
            }
            return UserCandleListener(adapter)
        }
    }

    @Autowired
    lateinit var kafka: KafkaTemplate<String, Any>

    @Autowired
    lateinit var config: TestConfig

    @Test
    fun `UserCandleUpdatedEvent publish 후 listener가 Redis ZADD upsertAll 호출`() {
        val snapshots =
            listOf(
                CandleSnapshot(
                    openTime = Instant.ofEpochSecond(1_700_000_000L),
                    open = BigDecimal("71000"),
                    high = BigDecimal("72000"),
                    low = BigDecimal("70000"),
                    close = BigDecimal("71500"),
                    volume = 10_000_000L,
                ),
                CandleSnapshot(
                    openTime = Instant.ofEpochSecond(1_700_086_400L),
                    open = BigDecimal("71500"),
                    high = BigDecimal("73000"),
                    low = BigDecimal("71000"),
                    close = BigDecimal("72500"),
                    volume = 8_000_000L,
                ),
            )

        val event =
            UserCandleUpdatedEvent(
                userId = "74683223",
                code = "005930",
                interval = "D1",
                candles = snapshots,
                occurredAt = Instant.now(),
            )

        kafka.send("market.candle.user.v1", "005930", event).get()

        // listener가 upsertAll을 호출할 때까지 최대 15초 대기
        await()
            .atMost(Duration.ofSeconds(15))
            .until { config.receivedEvents.any { it.code == "005930" && it.userId == "74683223" } }

        verify(exactly = 1) {
            config.cacheAdapterMock.upsertAll(
                "74683223",
                "005930",
                "D1",
                match { it.size == 2 },
            )
        }
    }

    @Test
    fun `listener Redis 예외 시 메시지 손실 허용 - 예외 전파 안 함`() {
        // cacheAdapter.upsertAll이 예외를 던지도록 override
        every {
            config.cacheAdapterMock.upsertAll(any(), eq("THROW_TEST"), any(), any())
        } throws RuntimeException("Redis 연결 실패")

        val event =
            UserCandleUpdatedEvent(
                userId = "74683223",
                code = "THROW_TEST",
                interval = "D1",
                candles =
                    listOf(
                        CandleSnapshot(
                            openTime = Instant.ofEpochSecond(1_700_000_000L),
                            open = BigDecimal("1000"),
                            high = BigDecimal("1100"),
                            low = BigDecimal("900"),
                            close = BigDecimal("1050"),
                            volume = 1_000L,
                        ),
                    ),
                occurredAt = Instant.now(),
            )

        // 예외 전파 없이 정상 처리돼야 한다 (fail-soft)
        // EmbeddedKafka 비동기 특성상 직접 listener를 invoke해서 검증
        val listener = UserCandleListener(config.cacheAdapterMock)
        listener.onUserCandleUpdated(event) // 예외 던지면 테스트 실패
    }
}

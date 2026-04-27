package com.mj.consumer.messaging

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
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
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.stereotype.Component
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TickPublisher → market.tick.v1 토픽 → TestTickReceiver 왕복 스모크 테스트.
 *
 * 최소 컨텍스트: Kafka + Jackson 자동설정만 로드, 나머지는 테스트 Config에서 직접 구성.
 * 외부 의존성(KIS, DB, AI 등)을 완전히 배제하여 빠르게 실행된다.
 */
@SpringBootTest(classes = [TickRoundtripIT.TestConfig::class, TestTickReceiver::class])
@EmbeddedKafka(
    partitions = 1,
    topics = ["market.tick.v1"],
    brokerProperties = ["listeners=PLAINTEXT://localhost:0", "port=0"],
)
@TestPropertySource(
    properties = [
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=tick-roundtrip-test",
        "magicjar.kafka.topics.tick=market.tick.v1",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=com.mj",
        "spring.kafka.consumer.properties.spring.json.use.type.headers=false",
        "spring.kafka.consumer.properties.spring.json.value.default.type=com.mj.domain.market.event.TickEvent",
    ],
)
class TickRoundtripIT {
    @Configuration
    @EnableKafka
    @ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
    class TestConfig {
        @Bean
        fun tickPublisher(kafka: KafkaTemplate<String, Any>): TickPublisher = TickPublisher(kafka, "market.tick.v1")
    }

    @Autowired
    lateinit var tickPublisher: TickPublisher

    @Autowired
    lateinit var receiver: TestTickReceiver

    @Test
    fun `tick publish should be received by listener`() {
        val event =
            TickEvent(
                code = "005930",
                at = Instant.now(),
                price = BigDecimal("72500"),
                volume = 100L,
            )

        tickPublisher.publish(event)

        await()
            .atMost(Duration.ofSeconds(15))
            .until { receiver.received.any { it.code == "005930" } }
    }
}

@Component
class TestTickReceiver {
    val received: MutableList<TickEvent> = CopyOnWriteArrayList()

    @KafkaListener(topics = ["market.tick.v1"], groupId = "tick-roundtrip-test")
    fun onTick(event: TickEvent) {
        received.add(event)
    }
}

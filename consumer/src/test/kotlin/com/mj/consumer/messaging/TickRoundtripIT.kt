package com.mj.consumer.messaging

import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.test.context.EmbeddedKafka
import org.springframework.stereotype.Component
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.TestPropertySource
import java.math.BigDecimal
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * TickPublisher → market.tick.v1 토픽 → TestTickReceiver 왕복 스모크 테스트.
 *
 * EmbeddedKafka 사용 — 외부 Kafka 브로커 불필요.
 */
@SpringBootTest
@EmbeddedKafka(
    partitions = 1,
    topics = ["market.tick.v1"],
    brokerProperties = ["listeners=PLAINTEXT://localhost:0", "port=0"],
)
@ActiveProfiles("test")
@TestPropertySource(
    properties = [
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=tick-roundtrip-test",
        "magicjar.kafka.topics.tick=market.tick.v1",
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration," +
            "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration," +
            "org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration," +
            "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration",
        "spring.security.enabled=false",
        "kis.app-key=",
        "kis.app-secret=",
        "spring.ai.openai.api-key=",
    ],
)
class TickRoundtripIT {
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
            .atMost(Duration.ofSeconds(10))
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

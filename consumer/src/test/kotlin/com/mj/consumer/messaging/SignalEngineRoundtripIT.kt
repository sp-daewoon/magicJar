package com.mj.consumer.messaging

import com.mj.consumer.application.signal.ActiveStrategyRegistry
import com.mj.consumer.application.signal.CandleBuffer
import com.mj.consumer.application.signal.SignalEngineExecutor
import com.mj.consumer.application.signal.StrategySubscription
import com.mj.consumer.messaging.listener.SignalEngineListener
import com.mj.consumer.messaging.producer.SignalGeneratedPublisher
import com.mj.consumer.messaging.producer.TickPublisher
import com.mj.domain.market.event.TickEvent
import com.mj.domain.strategy.dsl.Condition
import com.mj.domain.strategy.dsl.ConditionOperator
import com.mj.domain.strategy.dsl.ConstantOperand
import com.mj.domain.strategy.dsl.LogicalGroup
import com.mj.domain.strategy.dsl.LogicalOp
import com.mj.domain.strategy.dsl.PriceField
import com.mj.domain.strategy.dsl.PriceOperand
import com.mj.domain.strategy.dsl.StrategyBody
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.dsl.StrategyMetadata
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import com.mj.domain.strategy.signal.SignalEngine
import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
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
 * 트랙11 R1 슬라이스 IT — tick → signal.generated.v1 왕복 (EmbeddedKafka).
 *
 * - TickPublisher → market.tick.v1 → SignalEngineListener → SignalEngineExecutor →
 *   SignalGeneratedPublisher → signal.generated.v1 → TestSignalReceiver
 * - 항상 BUY 전략을 등록해 발행을 결정론적으로 검증.
 *
 * 외부 의존성(DB·Redis·Anthropic·KIS) 0. Kafka·Jackson autoconfig만.
 */
@SpringBootTest(
    classes = [
        SignalEngineRoundtripIT.TestConfig::class,
        TestSignalReceiver::class,
    ],
)
@EmbeddedKafka(
    partitions = 1,
    topics = ["market.tick.v1", "signal.generated.v1"],
    brokerProperties = ["listeners=PLAINTEXT://localhost:0", "port=0"],
)
@TestPropertySource(
    properties = [
        "spring.kafka.consumer.auto-offset-reset=earliest",
        "spring.kafka.consumer.group-id=signal-engine-rt-test",
        "magicjar.kafka.topics.tick=market.tick.v1",
        "magicjar.kafka.topics.signalGenerated=signal.generated.v1",
        "magicjar.kafka.consumer-groups.signal-engine=signal-engine-rt",
        "spring.kafka.producer.value-serializer=org.springframework.kafka.support.serializer.JsonSerializer",
        "spring.kafka.consumer.value-deserializer=org.springframework.kafka.support.serializer.JsonDeserializer",
        "spring.kafka.consumer.properties.spring.json.trusted.packages=*",
    ],
)
class SignalEngineRoundtripIT {
    @Configuration
    @EnableKafka
    @ImportAutoConfiguration(KafkaAutoConfiguration::class, JacksonAutoConfiguration::class)
    class TestConfig {
        @Bean
        fun tickPublisher(kafka: KafkaTemplate<String, Any>): TickPublisher = TickPublisher(kafka, "market.tick.v1")

        @Bean
        fun signalGeneratedPublisher(kafka: KafkaTemplate<String, Any>): SignalGeneratedPublisher =
            SignalGeneratedPublisher(kafka, "signal.generated.v1")

        @Bean
        fun candleBuffer(): CandleBuffer = CandleBuffer()

        @Bean
        fun activeStrategyRegistry(): ActiveStrategyRegistry =
            ActiveStrategyRegistry(
                initialSubscriptions =
                    listOf(
                        StrategySubscription(
                            userId = "rt-user",
                            strategyDsl = alwaysBuyStrategy(),
                        ),
                    ),
            )

        @Bean
        fun indicatorEngine(): IndicatorEngine = IndicatorEngine(listOf(NativeIndicatorCalculator()))

        @Bean
        fun strategyDslEvaluator(indicatorEngine: IndicatorEngine): StrategyDslEvaluator = StrategyDslEvaluator(indicatorEngine)

        @Bean
        fun signalEngine(dslEvaluator: StrategyDslEvaluator): SignalEngine = SignalEngine(dslEvaluator)

        @Bean
        fun signalEngineExecutor(
            signalEngine: SignalEngine,
            candleBuffer: CandleBuffer,
            activeStrategyRegistry: ActiveStrategyRegistry,
            publisher: SignalGeneratedPublisher,
        ): SignalEngineExecutor =
            SignalEngineExecutor(
                signalEngine = signalEngine,
                candleBuffer = candleBuffer,
                activeStrategyRegistry = activeStrategyRegistry,
                publisher = publisher,
            )

        @Bean
        fun signalEngineListener(executor: SignalEngineExecutor): SignalEngineListener = SignalEngineListener(executor)
    }

    @Autowired
    lateinit var tickPublisher: TickPublisher

    @Autowired
    lateinit var receiver: TestSignalReceiver

    @Test
    fun `tick publish triggers signal generated event roundtrip`() {
        val tick =
            TickEvent(
                code = "005930",
                at = Instant.parse("2026-05-03T09:00:00Z"),
                price = BigDecimal("72500"),
                volume = 100L,
                source = "TEST",
            )

        tickPublisher.publish(tick)

        await()
            .atMost(Duration.ofSeconds(20))
            .until { receiver.received.any { it.symbol == "005930" && it.action == "BUY" } }

        val signal = receiver.received.first { it.symbol == "005930" }
        assert(signal.strategyId == "always_buy")
        assert(signal.userId == "rt-user")
        assert(signal.strength == 1.0)
        assert(signal.reasons.isNotEmpty())
    }

    companion object {
        fun alwaysBuyStrategy(): StrategyDsl =
            StrategyDsl(
                version = "1.0",
                metadata = StrategyMetadata(name = "always_buy"),
                strategy =
                    StrategyBody(
                        id = "always_buy",
                        entry =
                            LogicalGroup(
                                logic = LogicalOp.AND,
                                nodes =
                                    listOf(
                                        Condition(
                                            lhs = PriceOperand(field = PriceField.CLOSE),
                                            operator = ConditionOperator.GREATER_EQUAL,
                                            rhs = ConstantOperand(BigDecimal.ZERO),
                                        ),
                                    ),
                            ),
                        exit =
                            LogicalGroup(
                                logic = LogicalOp.AND,
                                nodes =
                                    listOf(
                                        Condition(
                                            lhs = PriceOperand(field = PriceField.CLOSE),
                                            operator = ConditionOperator.LESS_THAN,
                                            rhs = ConstantOperand(BigDecimal("-1")),
                                        ),
                                    ),
                            ),
                    ),
            )
    }
}

/**
 * signal.generated.v1 토픽 수신 stub.
 */
@Component
class TestSignalReceiver {
    val received: MutableList<SignalGeneratedEvent> = CopyOnWriteArrayList()

    @KafkaListener(
        topics = ["signal.generated.v1"],
        groupId = "signal-engine-rt-receiver",
    )
    fun onSignal(event: SignalGeneratedEvent) {
        received.add(event)
    }
}

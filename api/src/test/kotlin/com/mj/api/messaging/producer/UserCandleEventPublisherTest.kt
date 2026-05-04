package com.mj.api.messaging.producer

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.market.event.UserCandleUpdatedEvent
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.SendResult
import java.math.BigDecimal
import java.time.Instant
import java.util.concurrent.CompletableFuture

/**
 * UserCandleEventPublisher 단위 테스트 — ADR 0050 R3-4.
 *
 * 시나리오:
 *   1. 정상 발행 — 캔들 목록 → UserCandleUpdatedEvent 변환 후 KafkaTemplate.send 호출
 *   2. 빈 캔들 목록 — send 호출 0건 (no-op)
 *   3. KafkaTemplate 예외 — fail-soft (예외 전파 안 함)
 */
class UserCandleEventPublisherTest {
    private lateinit var kafka: KafkaTemplate<String, Any>
    private lateinit var publisher: UserCandleEventPublisher

    private val topic = "market.candle.user.v1"

    @BeforeEach
    fun setUp() {
        kafka = mockk()
        publisher = UserCandleEventPublisher(kafka, topic)
    }

    private fun makeCandle(epochSecond: Long = 1_700_000_000L) =
        Candle(
            code = "005930",
            interval = Interval.D1,
            openTime = Instant.ofEpochSecond(epochSecond),
            open = BigDecimal("71000"),
            high = BigDecimal("72000"),
            low = BigDecimal("70000"),
            close = BigDecimal("71500"),
            volume = 10_000_000L,
            tradeCount = 1000L,
        )

    @Test
    fun `캔들 목록 전달 시 UserCandleUpdatedEvent로 변환해 KafkaTemplate send 호출`() {
        val candles = listOf(makeCandle(1_700_000_000L), makeCandle(1_700_086_400L))

        val future: CompletableFuture<SendResult<String, Any>> = CompletableFuture.completedFuture(mockk())
        val eventSlot = slot<UserCandleUpdatedEvent>()
        every { kafka.send(topic, "005930", capture(eventSlot)) } returns future

        publisher.publish(
            userId = "74683223",
            code = "005930",
            interval = "D1",
            candles = candles,
        )

        verify(exactly = 1) { kafka.send(topic, "005930", any<UserCandleUpdatedEvent>()) }

        val capturedEvent = eventSlot.captured
        capturedEvent.userId shouldBe "74683223"
        capturedEvent.code shouldBe "005930"
        capturedEvent.interval shouldBe "D1"
        capturedEvent.candles shouldHaveSize 2
        capturedEvent.candles[0].openTime shouldBe Instant.ofEpochSecond(1_700_000_000L)
        capturedEvent.candles[0].close shouldBe BigDecimal("71500")
        capturedEvent.candles[0].volume shouldBe 10_000_000L
    }

    @Test
    fun `빈 캔들 목록이면 KafkaTemplate send 호출 0건`() {
        publisher.publish(
            userId = "74683223",
            code = "005930",
            interval = "D1",
            candles = emptyList(),
        )

        verify(exactly = 0) { kafka.send(any<String>(), any<String>(), any()) }
    }

    @Test
    fun `KafkaTemplate send 예외 시 fail-soft — 예외 전파 안 함`() {
        every { kafka.send(any<String>(), any<String>(), any()) } throws RuntimeException("Kafka broker 연결 실패")

        // 예외 전파 없이 정상 반환해야 한다
        publisher.publish(
            userId = "74683223",
            code = "005930",
            interval = "D1",
            candles = listOf(makeCandle()),
        )
    }
}

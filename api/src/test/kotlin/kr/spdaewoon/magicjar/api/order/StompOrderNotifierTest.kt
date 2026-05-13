package kr.spdaewoon.magicjar.api.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.api.messaging.StompOrderNotifier
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.verify
import org.springframework.messaging.simp.SimpMessagingTemplate

/**
 * StompOrderNotifierTest — ADR 0068 §15-6 LOCK 4 event discriminator 케이스.
 *
 * 4 topic → 4 event type → STOMP 발행 검증
 *
 * // AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
class StompOrderNotifierTest {
    private val messagingTemplate: SimpMessagingTemplate = Mockito.mock(SimpMessagingTemplate::class.java)
    private val objectMapper = ObjectMapper()
    private val sut = StompOrderNotifier(messagingTemplate, objectMapper)

    private fun makeRecord(
        topic: String,
        userId: String,
        orderId: Long = 1L,
    ): ConsumerRecord<String, String> {
        val payload =
            objectMapper.writeValueAsString(
                mapOf(
                    "userId" to userId,
                    "orderId" to orderId,
                    "code" to "005930",
                    "side" to "BUY",
                    "quantity" to 10,
                ),
            )
        return ConsumerRecord(topic, 0, 0L, "key", payload)
    }

    @Test
    fun `order_requested_v1 → REQUESTED event → STOMP 발행`() {
        val topic = "order.requested.v1"
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq("/topic/order.user.$userId"), any<Any>())
    }

    @Test
    fun `order_filled_v1 → FILLED event → STOMP 발행`() {
        val topic = "order.filled.v1"
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq("/topic/order.user.$userId"), any<Any>())
    }

    @Test
    fun `order_rejected_v1 → REJECTED event → STOMP 발행`() {
        val topic = "order.rejected.v1"
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq("/topic/order.user.$userId"), any<Any>())
    }

    @Test
    fun `order_cancelled_v1 → CANCELLED event → STOMP 발행`() {
        val topic = "order.cancelled.v1"
        val userId = "user1"

        sut.onOrderEvent(makeRecord(topic, userId), topic)

        verify(messagingTemplate).convertAndSend(eq("/topic/order.user.$userId"), any<Any>())
    }
}

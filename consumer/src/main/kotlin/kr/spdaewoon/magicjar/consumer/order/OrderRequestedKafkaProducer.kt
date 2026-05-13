package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `order.requested.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK).
 *
 * OrderRouterKafkaListener step 6에서 publish — ADR 0044 Notification 진입점.
 * Phase 2 backend-core가 listen → STOMP `/topic/order.user.{userId}` REQUESTED fanout.
 *
 * partition key = userId.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderRequestedKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderRequestedKafkaProducer::class.java)

    companion object {
        const val TOPIC = "order.requested.v1"
    }

    fun publish(request: OrderRequest) {
        try {
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "orderId" to request.id,
                        "userId" to request.userId,
                        "signalId" to request.signalId,
                        "strategyId" to request.strategyId,
                        "code" to request.code,
                        "side" to request.side.name,
                        "priceType" to request.priceType.name,
                        "quantity" to request.quantity,
                        "limitPrice" to request.limitPrice?.toPlainString(),
                        "status" to request.status.name,
                        "mode" to request.mode.name,
                        "requestedAt" to request.requestedAt.epochSecond,
                    ),
                )
            kafkaTemplate.send(TOPIC, request.userId, payload)
            log.debug(
                "OrderRequestedKafkaProducer publish: topic={} userId={} orderId={} code={}",
                TOPIC,
                request.userId,
                request.id,
                request.code,
            )
        } catch (e: Exception) {
            log.error(
                "OrderRequestedKafkaProducer publish 실패: userId={} orderId={} err={}",
                request.userId,
                request.id,
                e.message,
            )
        }
    }
}

package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderExecution
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `order.filled.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK + T-RECON 합류점).
 *
 * KisExecutionWsListener CNTG_YN="2" full fill 시 publish.
 * Phase 2 backend-core listen → STOMP FILLED fanout + ADR 0044 Notification.
 *
 * partition key = userId.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderFilledKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderFilledKafkaProducer::class.java)

    companion object {
        const val TOPIC = "order.filled.v1"
    }

    fun publish(
        request: OrderRequest,
        execution: OrderExecution,
        partial: Boolean,
    ) {
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
                        "executionId" to execution.id,
                        "kisOrderNo" to request.kisOrderNo,
                        "executedQuantity" to execution.executedQuantity,
                        "executedPrice" to execution.executedPrice.toPlainString(),
                        "executedAt" to execution.executedAt.epochSecond,
                        "partial" to partial,
                        "mode" to request.mode.name,
                    ),
                )
            kafkaTemplate.send(TOPIC, request.userId, payload)
            log.info(
                "OrderFilledKafkaProducer publish: topic={} userId={} orderId={} partial={} qty={}",
                TOPIC,
                request.userId,
                request.id,
                partial,
                execution.executedQuantity,
            )
        } catch (e: Exception) {
            log.error(
                "OrderFilledKafkaProducer publish 실패: userId={} orderId={} err={}",
                request.userId,
                request.id,
                e.message,
            )
        }
    }
}

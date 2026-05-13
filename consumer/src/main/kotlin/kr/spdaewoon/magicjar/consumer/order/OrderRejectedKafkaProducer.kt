package kr.spdaewoon.magicjar.consumer.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `order.rejected.v1` Kafka publish 어댑터 (ADR 0068 §9-2 매트릭스 LOCK + ADR 0044 진입).
 *
 * §14-2 step 3·4·7 REJECTED 분기 시 publish.
 * payload는 rejectReason 포함 (REAL_GATE_DENIED:cond / KILLSWITCH_ON / rt_cd:msg / KIS_5XX 등).
 *
 * partition key = userId.
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8)
 */
@Component
class OrderRejectedKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(OrderRejectedKafkaProducer::class.java)

    companion object {
        const val TOPIC = "order.rejected.v1"
    }

    fun publish(
        request: OrderRequest,
        reason: String,
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
                        "reason" to reason,
                        "mode" to request.mode.name,
                        "rejectedAt" to (request.completedAt?.epochSecond ?: request.requestedAt.epochSecond),
                    ),
                )
            kafkaTemplate.send(TOPIC, request.userId, payload)
            log.warn(
                "OrderRejectedKafkaProducer publish: topic={} userId={} orderId={} reason={}",
                TOPIC,
                request.userId,
                request.id,
                reason,
            )
        } catch (e: Exception) {
            log.error(
                "OrderRejectedKafkaProducer publish 실패: userId={} orderId={} err={}",
                request.userId,
                request.id,
                e.message,
            )
        }
    }
}

package kr.spdaewoon.magicjar.api.messaging.order

import com.fasterxml.jackson.databind.ObjectMapper
import kr.spdaewoon.magicjar.domain.order.execution.OrderIntent
import org.slf4j.LoggerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * 수동 주문 의도(order.intent.v1) Kafka publish 어댑터 (ADR 0082 T-MAN B-2-extended LOCK).
 *
 * 토픽 재사용: 자동 path([kr.spdaewoon.magicjar.consumer.risk.OrderIntentKafkaProducer])와
 * 동일 토픽 `order.intent.v1` publish — consumer OrderRouterKafkaListener step 2가
 * payload "source" 필드("AUTO"/"MANUAL")로 분기 분류.
 *
 * partition key = `userId` — 사용자별 ordering 보장 (자동 path와 동일).
 *
 * payload schema (ADR 0082 §3-3 LOCK — 자동 path 호환 + source 1 필드 add):
 *   {
 *     "signalId": 0,                  // MANUAL sentinel
 *     "strategyId": 0,                // MANUAL sentinel
 *     "userId": "...",
 *     "code": "005930",
 *     "interval": "1d",               // MANUAL 시 placeholder (consumer step 2 deserialize 통과 목적)
 *     "ts": <epochSeconds>,
 *     "side": "BUY",
 *     "strength": 0.0,                // MANUAL 시 placeholder
 *     "priceAtSignal": "0",           // MANUAL 시 placeholder
 *     "riskVerdict": "ALLOWED",       // MANUAL 시 룰 우회 LOCK (사용자 책임)
 *     "priceType": "MARKET",          // MANUAL 핵심 필드 (자동 path는 기본 MARKET)
 *     "quantity": 1,
 *     "limitPrice": "70500" | null,
 *     "idempotencyKey": "manual:userId:epochSeconds",
 *     "source": "MANUAL",             // ★ ADR 0082 핵심 분기 마커
 *     "emittedAt": <epochSeconds>
 *   }
 *
 * AI 호출 0 — T-O 트랙 정책 (ADR 0023 §2-4 + ADR 0066 §8 + ADR 0067 §8 + ADR 0068 §8 + ADR 0082)
 */
@Component
class ManualOrderIntentKafkaProducer(
    private val kafkaTemplate: KafkaTemplate<String, String>,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(ManualOrderIntentKafkaProducer::class.java)

    companion object {
        const val TOPIC = "order.intent.v1"
    }

    /**
     * 수동 주문 의도 publish.
     *
     * @param intent OrderIntent (source=MANUAL LOCK — service 레이어에서 채움)
     * @throws RuntimeException Kafka publish 실패 시 — endpoint 503 응답 매핑
     */
    fun publish(intent: OrderIntent) {
        require(intent.source.name == "MANUAL") {
            "ManualOrderIntentKafkaProducer는 source=MANUAL OrderIntent만 publish 가능 (실제=${intent.source})"
        }
        try {
            val now =
                java.time.Instant
                    .now()
                    .epochSecond
            val payload =
                objectMapper.writeValueAsString(
                    mapOf(
                        "signalId" to intent.signalId,
                        "strategyId" to intent.strategyId,
                        "userId" to intent.userId,
                        "code" to intent.code,
                        "interval" to "1d",
                        "ts" to now,
                        "side" to intent.side.name,
                        "strength" to 0.0,
                        "priceAtSignal" to "0",
                        "riskVerdict" to "ALLOWED",
                        "priceType" to intent.priceType.name,
                        "quantity" to intent.quantity,
                        "limitPrice" to intent.limitPrice?.toPlainString(),
                        "idempotencyKey" to intent.idempotencyKey,
                        "source" to intent.source.name,
                        "emittedAt" to now,
                    ),
                )
            kafkaTemplate.send(TOPIC, intent.userId, payload).get()
            log.info(
                "ManualOrderIntentKafkaProducer publish OK: topic={} userId={} code={} side={} priceType={} qty={} idempotency={}",
                TOPIC,
                intent.userId,
                intent.code,
                intent.side,
                intent.priceType,
                intent.quantity,
                intent.idempotencyKey,
            )
        } catch (e: Exception) {
            log.error(
                "ManualOrderIntentKafkaProducer publish 실패: userId={} code={} err={}",
                intent.userId,
                intent.code,
                e.message,
            )
            throw RuntimeException("KAFKA_PUBLISH_FAILED: ${e.message}", e)
        }
    }
}

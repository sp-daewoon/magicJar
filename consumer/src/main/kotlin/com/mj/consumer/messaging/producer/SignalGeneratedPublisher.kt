package com.mj.consumer.messaging.producer

import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `signal.generated.v1` 토픽으로 Signal Engine 결과를 발행한다 (트랙11 R1, ADR 0041).
 *
 * - Kafka key = symbol (파티셔닝 — 같은 종목 시그널은 같은 파티션)
 * - 1번 트랙 Kafka 패턴 정합 (camelCase + Type-Id 헤더)
 *
 * 기존 [SignalPublisher] (`signal.v1`)와 별도 토픽. SignalEvent와 SignalGeneratedEvent는
 * 스키마가 다르므로 호환성 단절 — 신규 토픽으로 분리.
 */
@Component
class SignalGeneratedPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.signalGenerated:signal.generated.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(SignalGeneratedPublisher::class.java)

    fun publish(event: SignalGeneratedEvent) {
        kafka
            .send(topic, event.symbol, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn(
                        "SignalGeneratedPublisher send failed (symbol={}, strategy={}): {}",
                        event.symbol,
                        event.strategyId,
                        ex.message,
                    )
                } else {
                    log.debug(
                        "SignalGeneratedPublisher sent (symbol={}, strategy={}, action={})",
                        event.symbol,
                        event.strategyId,
                        event.action,
                    )
                }
            }
    }
}

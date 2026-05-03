package com.mj.consumer.messaging.listener

import com.mj.consumer.application.signal.SignalEngineExecutor
import com.mj.domain.market.event.TickEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `market.tick.v1` → SignalEngineExecutor → `signal.generated.v1` (트랙11 R1).
 *
 * 별도 group-id 사용 — TickAggregator·StompFanout과 독립적으로 offset 진행.
 */
@Component
class SignalEngineListener(
    private val executor: SignalEngineExecutor,
) {
    private val log = LoggerFactory.getLogger(SignalEngineListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.tick:market.tick.v1}"],
        groupId = "\${magicjar.kafka.consumer-groups.signal-engine:magicjar-signal-engine}",
    )
    fun onTick(event: TickEvent) {
        try {
            executor.onTick(event)
        } catch (ex: Exception) {
            log.warn("SignalEngineListener onTick error (code={}): {}", event.code, ex.message)
        }
    }
}

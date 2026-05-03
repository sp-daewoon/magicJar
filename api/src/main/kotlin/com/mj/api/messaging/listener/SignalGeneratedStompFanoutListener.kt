package com.mj.api.messaging.listener

import com.mj.api.application.signal.SignalHistoryService
import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * `signal.generated.v1` → STOMP fanout + 이력 누적 (트랙11 R1, ADR 0041).
 *
 * 토픽 분기:
 *   - userId 있는 시그널 → `/topic/signal.{userId}`
 *   - userId 없는(anonymous) 시그널 → `/topic/signal`
 *
 * R1 anonymous default — `userId == null` 케이스를 수용한다 (ActiveStrategyRegistry default subscription).
 * R2에서 DB 영속 + 사용자별 라우팅 본격화.
 */
@Component
class SignalGeneratedStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
    private val history: SignalHistoryService,
) {
    private val log = LoggerFactory.getLogger(SignalGeneratedStompFanoutListener::class.java)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.signalGenerated:signal.generated.v1}"],
        groupId = "magicjar-api-stomp",
    )
    fun onSignalGenerated(event: SignalGeneratedEvent) {
        history.record(event)
        val destination = if (event.userId != null) "/topic/signal.${event.userId}" else "/topic/signal"
        stomp.convertAndSend(destination, event)
        log.debug(
            "SignalGeneratedStompFanout → {} (symbol={}, action={}, strategy={})",
            destination,
            event.symbol,
            event.action,
            event.strategyId,
        )
    }
}

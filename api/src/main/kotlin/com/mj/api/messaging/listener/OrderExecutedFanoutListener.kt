package com.mj.api.messaging.listener

import com.mj.domain.order.event.OrderExecutedEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * order.executed.v1 → STOMP /topic/orders/{userId} fanout. spec §6.3, §12.
 *
 * 1차에는 모든 사용자가 default 시드 1명이라 단일 토픽으로 fanout.
 * 인증 도입 시 OrderExecutedEvent에 userId 컬럼 추가 또는 별도 lookup 후 user별 토픽 분기.
 *
 * group-id를 consumer 모듈과 다르게 — 두 곳에서 동시에 같은 메시지 처리.
 */
@Component
class OrderExecutedFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.orderExecuted:order.executed.v1}"],
        groupId = "\${magicjar.kafka.api-group-id:magicjar-api}",
    )
    fun onOrderExecuted(event: OrderExecutedEvent) {
        log.debug(
            "[STOMP_FANOUT] order.executed.v1 → /topic/orders (orderId={}, status={}, qty={})",
            event.orderId,
            event.status,
            event.executedQty,
        )
        stomp.convertAndSend("/topic/orders", event)
    }
}

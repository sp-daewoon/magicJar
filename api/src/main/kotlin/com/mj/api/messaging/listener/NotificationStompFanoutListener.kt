package com.mj.api.messaging.listener

import com.mj.domain.notification.NotificationEvent
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.simp.SimpMessagingTemplate
import org.springframework.stereotype.Component

/**
 * `notifications.v1` → STOMP `/topic/notifications` fan-out.
 *
 * 모든 사용자 알림(subscription eviction, order, system)을 하나의 STOMP 토픽에
 * fanout. frontend는 `useNotificationsStream`으로 단일 구독.
 */
@Component
class NotificationStompFanoutListener(
    private val stomp: SimpMessagingTemplate,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.notifications:notifications.v1}"],
        groupId = "magicjar-api-notification-stomp",
    )
    fun onNotification(event: NotificationEvent) {
        stomp.convertAndSend("/topic/notifications", event)
        log.debug(
            "NotificationStompFanout → /topic/notifications id={} category={} level={}",
            event.id,
            event.category,
            event.level,
        )
    }
}

package com.mj.consumer.messaging.producer

import com.mj.domain.notification.NotificationEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `notifications.v1` 토픽으로 사용자 알림 이벤트를 발행한다.
 *
 * 사용처:
 *   - SubscriptionEvictedAdapter (LRU eviction → 사용자 toast)
 *   - 후속: 주문 거부, KIS 키 만료, 마스터 sync 실패 등 시스템 알림 통합
 */
@Component
class NotificationPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.notifications:notifications.v1}") private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    fun publish(event: NotificationEvent) {
        kafka
            .send(topic, event.id, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[NOTIFICATION] send failed id={}: {}", event.id, ex.message)
                } else {
                    log.debug("[NOTIFICATION] sent id={} category={} title={}", event.id, event.category, event.title)
                }
            }
    }
}

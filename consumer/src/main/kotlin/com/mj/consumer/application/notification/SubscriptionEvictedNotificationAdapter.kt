package com.mj.consumer.application.notification

import com.mj.consumer.messaging.producer.NotificationPublisher
import com.mj.domain.market.event.SubscriptionEvictedEvent
import com.mj.domain.notification.NotificationEvent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * `SubscriptionEvictedEvent` (in-process) → `NotificationEvent` 변환 + Kafka publish.
 *
 * 흐름:
 *   1. SubscriptionManager가 LRU 한도 초과 시 in-process publishEvent
 *   2. 본 어댑터가 @EventListener로 받아 사용자 친화 message로 가공
 *   3. NotificationPublisher → Kafka notifications.v1
 *   4. api 측 NotificationStompFanoutListener → STOMP /topic/notifications
 *   5. frontend useNotificationsStream → toast 표시
 *
 * Profile dev|prd 한정 (SubscriptionEvictedEvent는 SubscriptionManager dev|prd에서만 발행).
 *
 * 도메인 이벤트는 Spring ApplicationEvent를 상속하지 않으므로 (hexagonal 정합)
 * `@EventListener` annotation으로 직접 type 매칭한다.
 */
@Component
@Profile("dev | prd")
class SubscriptionEvictedNotificationAdapter(
    private val publisher: NotificationPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onEvicted(event: SubscriptionEvictedEvent) {
        val notification =
            NotificationEvent(
                level = NotificationEvent.LEVEL_WARN,
                category = NotificationEvent.CATEGORY_SUBSCRIPTION,
                title = "실시간 시세 한도 도달",
                body = "${event.evictedCode} 종목의 실시간 시세 구독이 해제되었습니다 (LRU 18 한도). 다시 보시려면 종목을 검색해 진입하세요.",
            )
        try {
            publisher.publish(notification)
            log.info("[NOTIFICATION] subscription eviction → publish id={} code={}", notification.id, event.evictedCode)
        } catch (e: Exception) {
            log.warn("[NOTIFICATION] publish failed code={}: {}", event.evictedCode, e.message)
        }
    }
}

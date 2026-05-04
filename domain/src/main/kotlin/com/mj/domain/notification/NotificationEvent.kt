package com.mj.domain.notification

import java.time.Instant
import java.util.UUID

/**
 * 사용자 화면에 toast/banner로 노출되는 알림 이벤트.
 *
 * Kafka 토픽: `notifications.v1`
 * STOMP fanout: `/topic/notifications`
 *
 * 발행 사례:
 *   - subscription 카테고리: SubscriptionManager LRU eviction (18 한도 초과)
 *   - 후속 확장: order(주문 거부 사유), system(KIS 키 만료, 마스터 sync 실패 등)
 *
 * @param id        UUID — frontend dedup·dismiss 추적
 * @param level     INFO | WARN | ERROR
 * @param category  subscription | order | system | ...
 * @param title     toast 헤드라인 (짧음)
 * @param body      세부 설명 (1~2문장)
 * @param at        발생 시각 (epoch ms 또는 ISO Instant)
 */
data class NotificationEvent(
    val id: String = UUID.randomUUID().toString(),
    val level: String,
    val category: String,
    val title: String,
    val body: String,
    val at: Instant = Instant.now(),
) {
    companion object {
        const val LEVEL_INFO: String = "INFO"
        const val LEVEL_WARN: String = "WARN"
        const val LEVEL_ERROR: String = "ERROR"

        const val CATEGORY_SUBSCRIPTION: String = "subscription"
        const val CATEGORY_ORDER: String = "order"
        const val CATEGORY_SYSTEM: String = "system"
    }
}

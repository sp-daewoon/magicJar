package com.mj.domain.market.event

/**
 * 18종목 한도 초과 시 LRU eviction으로 KIS WS 구독이 해제됐음을 알리는 도메인 이벤트.
 * plan T2-R2-A3 §T21, §2-C 정책.
 *
 * - default5(005930·000660·035720·005380·051910)는 eviction 면제.
 * - 향후 STOMP `/topic/notifications` fanout으로 frontend toast 표시 (R2-A2 또는 후속).
 *
 * @param evictedCode 해제된 종목코드
 * @param evictedAt   eviction 발생 시각 (epoch millis)
 * @param reason      "lru-over-limit"
 */
data class SubscriptionEvictedEvent(
    val evictedCode: String,
    val evictedAt: Long = System.currentTimeMillis(),
    val reason: String = "lru-over-limit",
)

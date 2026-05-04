package com.mj.api.messaging.producer

import com.mj.domain.market.event.MarketSubscriptionCommandEvent
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component

/**
 * `market.subscription.command.v1` 토픽으로 시세 구독 명령을 발행한다.
 *
 * 사용자가 종목 상세 진입 시 api가 호출 → consumer가 KIS WebSocket에 동적 subscribe.
 * 멱등 — KisWsRouter 측에서 중복 키는 frame 미전송.
 *
 * Quote hotfix B (R-B) — handoff 2026-05-04 P0.
 */
@Component
class MarketSubscriptionCommandPublisher(
    private val kafka: KafkaTemplate<String, Any>,
    @Value("\${magicjar.kafka.topics.marketSubscriptionCommand:market.subscription.command.v1}")
    private val topic: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * 종목 시세 구독 보장 명령을 비동기 발행한다. 실패 시 warn 로그만 남기고 무시 (best-effort).
     */
    fun ensureSubscribe(code: String) {
        val event = MarketSubscriptionCommandEvent(code = code)
        kafka
            .send(topic, code, event)
            .whenComplete { _, ex ->
                if (ex != null) {
                    log.warn("[SUBSCRIPTION_CMD] send failed code={}: {}", code, ex.message)
                } else {
                    log.debug("[SUBSCRIPTION_CMD] sent code={} topic={}", code, topic)
                }
            }
    }
}

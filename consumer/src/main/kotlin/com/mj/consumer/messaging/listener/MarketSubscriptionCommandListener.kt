package com.mj.consumer.messaging.listener

import com.mj.consumer.application.market.SubscriptionManager
import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.domain.market.event.MarketSubscriptionCommandEvent
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.stereotype.Component

/**
 * `market.subscription.command.v1` → KisWsRouter.subscribe 동적 구독 추가.
 *
 * 사용자가 종목 상세 진입 시 api가 명령을 발행하면 consumer가 KIS WebSocket에 H0UNCNT0
 * (체결가) trId로 추가 구독한다. KisWsRouter.subscribe는 멱등이라 같은 (trId, code)에
 * 대해 중복 명령이 와도 안전.
 *
 * Profile dev/prd 한정. mock profile은 jvm test fixture 전용으로 운영에서 활성 안 됨.
 *
 * Quote hotfix B (R-B) — handoff 2026-05-04 P0.
 */
@Component
@Profile("dev | prd")
class MarketSubscriptionCommandListener(
    private val subscriptionManager: SubscriptionManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @KafkaListener(
        topics = ["\${magicjar.kafka.topics.marketSubscriptionCommand:market.subscription.command.v1}"],
        groupId = "magicjar-consumer-subscription-cmd",
    )
    fun onCommand(event: MarketSubscriptionCommandEvent) {
        val trId = event.trId
        val code = event.code

        if (code.isBlank()) {
            log.warn("[SUBSCRIPTION_CMD] blank code — skip event={}", event)
            return
        }

        try {
            // 현재는 체결가(H0UNCNT0)만 지원. 호가/지수 trId는 R2-A2에서 추가.
            if (trId == KisQuoteWsAdapter.TR_ID) {
                subscriptionManager.addOnDemand(code)
                log.info("[SUBSCRIPTION_CMD] ensure-subscribe trId={} code={}", trId, code)
            } else {
                log.warn("[SUBSCRIPTION_CMD] unsupported trId={} code={} — skip", trId, code)
            }
        } catch (e: Exception) {
            log.warn("[SUBSCRIPTION_CMD] subscribe failed trId={} code={}: {}", trId, code, e.message)
        }
    }
}

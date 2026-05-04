package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisWsRouter
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 부팅 시 KisWsRouter 시작만 수행 — 자동 구독 0건 (ADR 0049).
 *
 * 사용자 정책 (2026-05-04):
 *   "최초엔 빈 상태. 사용자가 종목 입력(검색·클릭)하면 자동 구독 add. 보유 종목 자동 add도 폐기 —
 *    mount(상세 진입) 개념으로 일단 처리하고, 보유·watchlist 화면은 후속에서 별도 UI."
 *
 * 시세 구독 추가 경로:
 *   - 사용자 종목 진입 → api QuoteController.ensureSubscribe 발행 → MarketSubscriptionCommandListener
 *     → SubscriptionManager.addOnDemand
 *
 * 구독 trId: H0UNCNT0 (체결가). 호가(H0UNASP0)·지수(H0UPCNT0)는 R2-A2.
 */
@Component
@Profile("dev | prd")
class KisQuoteSubscriber(
    private val router: KisWsRouter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Spring Boot 완전 기동 후 KIS WS 연결 시작.
     * KisExecutionNoticeListener.onReady() 와 순서 무관 — router.start()는 멱등.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        router.start()
        log.info("[KIS_QUOTE_SUBSCRIBER] router started — bootstrap subscriptions: 0 (mount-only policy, ADR 0049)")
    }
}

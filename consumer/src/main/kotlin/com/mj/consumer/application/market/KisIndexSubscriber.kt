package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisIndexWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import com.mj.domain.market.event.IndexTickEvent
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 부팅 시 KOSPI·KOSDAQ 지수를 자동 구독한다.
 *
 * ADR 0049 정책:
 *   - 종목(stock)은 mount-only — 사용자 진입 시 add (KisQuoteSubscriber·SubscriptionManager).
 *   - **지수(index)는 시장 전체 지표** — Dashboard 헤더 상시 표시 → 부팅 자동 구독 OK.
 *
 * 구독 대상:
 *   - `0001` (KOSPI) trId=H0UPCNT0
 *   - `1001` (KOSDAQ) trId=H0UPCNT0
 *
 * router.start()는 KisQuoteSubscriber에서 호출 (멱등). 본 컴포넌트는 subscribe만 수행.
 *
 * Refs: plan T2-R2-A2 §T14, §2-A.
 */
@Component
@Profile("dev | prd")
class KisIndexSubscriber(
    private val router: KisWsRouter,
    private val indexAdapter: KisIndexWsAdapter,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        val codes = listOf(IndexTickEvent.KOSPI, IndexTickEvent.KOSDAQ)
        log.info("[KIS_INDEX_SUBSCRIBER] subscribing index codes: {}", codes)
        codes.forEach { code ->
            router.subscribe(KisIndexWsAdapter.TR_ID, code, indexAdapter)
        }
    }
}

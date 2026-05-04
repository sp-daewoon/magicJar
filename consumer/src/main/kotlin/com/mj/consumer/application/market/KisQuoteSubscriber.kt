package com.mj.consumer.application.market

import com.mj.consumer.infrastructure.kis.KisQuoteWsAdapter
import com.mj.consumer.infrastructure.kis.KisWsRouter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.annotation.Profile
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

/**
 * 부팅 시 KisWsRouter 시작 + 초기 시세 구독 bootstrap.
 *
 * 구독 종목 산출 (plan §2-D, §3 T5):
 *   holdings(보유) ∪ default5(고정) → distinct → take(18) = 초기 구독 목록
 *   watchlist는 R2-A3 도입 예정.
 *
 * 구독 trId: H0UNCNT0 (체결가) — R2-A1 범위.
 * 호가(H0UNASP0)·지수(H0UPCNT0)는 R2-A2에서 추가.
 *
 * plan §3 T5 (2026-05-04-track2-r2-quote-ws.md).
 */
@Component
@Profile("dev | prd")
class KisQuoteSubscriber(
    private val router: KisWsRouter,
    private val quoteListener: KisQuoteWsAdapter,
    private val holdingsRepo: HoldingsCodeFetcher,
    @Value("\${magicjar.market.default-codes:005930,000660,035720,005380,051910}")
    private val defaultCodesRaw: String,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    private val defaultCodes: List<String>
        get() = defaultCodesRaw.split(",").map { it.trim() }.filter { it.isNotBlank() }

    /**
     * Spring Boot 완전 기동 후 WS 연결 + 초기 구독.
     * KisExecutionNoticeListener.onReady() 와 순서 무관 — router.start()는 멱등.
     */
    @EventListener(ApplicationReadyEvent::class)
    fun onReady() {
        router.start()

        val holdingCodes =
            try {
                holdingsRepo.fetchHoldingCodes()
            } catch (e: Exception) {
                log.warn("[KIS_QUOTE_SUBSCRIBER] holdingsRepo.fetchHoldingCodes failed: {} — use empty", e.message)
                emptyList()
            }

        val codes =
            (holdingCodes + defaultCodes)
                .distinct()
                .take(MAX_SUBSCRIPTIONS)

        log.info("[KIS_QUOTE_SUBSCRIBER] subscribing {} code(s): {}", codes.size, codes)
        codes.forEach { code ->
            router.subscribe(KisQuoteWsAdapter.TR_ID, code, quoteListener)
        }
    }

    companion object {
        const val MAX_SUBSCRIPTIONS = 18
    }
}

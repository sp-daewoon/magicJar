package com.mj.domain.market.event

/**
 * 시장 시세 구독 요청 명령. api → consumer.
 *
 * Topic: `market.subscription.command.v1`
 *
 * 사용자가 종목 상세 진입 시 api가 발행 → consumer가 KIS WebSocket subscribe 추가.
 * KisWsRouter.subscribe는 멱등이므로 중복 발행되어도 안전.
 *
 * Quote hotfix B (R-B) — handoff 2026-05-04 P0.
 *
 * @param code        KRX 6자리 종목코드
 * @param trId        KIS WebSocket trId (default `H0UNCNT0` 체결가)
 * @param requestedAt 요청 시각 (epoch millis)
 */
data class MarketSubscriptionCommandEvent(
    val code: String,
    val trId: String = DEFAULT_TR_ID,
    val requestedAt: Long = System.currentTimeMillis(),
) {
    companion object {
        const val DEFAULT_TR_ID: String = "H0UNCNT0"
    }
}

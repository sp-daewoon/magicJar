package kr.spdaewoon.magicjar.domain.market.port

/**
 * KIS WS 41 stream slot 예산 결정 (ADR 0048 §2-1).
 * Pure function — Spring 의존 0.
 *
 * 슬롯 구성:
 *   1  = H0STCNI0 (체결통보 — ADR 0042 STAGE 0-4 reserve, T-O 구현)
 *   30 = H0STCNT0 (체결가 실시간 — 관심종목 WS 구독)
 *   10 = H0STASP0 (호가창 LRU — 선택)
 */
object SubscriptionPolicy {
    const val TOTAL_SLOTS = 41
    const val EXECUTION_NOTICE_RESERVED = 1 // H0STCNI0
    const val PRICE_TICK_MAX = 30 // H0STCNT0
    const val ORDERBOOK_MAX = 10 // H0STASP0 (LRU)

    fun priceTickSlotsAvailable(currentSubscribed: Int): Int = (PRICE_TICK_MAX - currentSubscribed).coerceAtLeast(0)
}

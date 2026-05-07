package kr.spdaewoon.magicjar.domain.market.port

/**
 * KIS WebSocket 구독 포트 (ADR 0048 정합 — 41건 구독 한도).
 * 구현은 consumer 모듈 ws 어댑터에서 (PR-8).
 *
 * 슬롯 예산:
 * - H0STCNT0 체결가: 최대 30 slot (subscribe/unsubscribe)
 * - H0STASP0 호가: 최대 10 slot (subscribeOrderbook/unsubscribeOrderbook) — PR-10b 신규
 */
interface KisWsPort {
    fun subscribe(codes: List<String>)

    fun unsubscribe(codes: List<String>)

    fun subscribedCodes(): Set<String>

    /**
     * KIS WS 호가 구독 (H0STASP0) — ADR 0048 §2-1 LRU 10 slot.
     * PR-10b 신규.
     */
    fun subscribeOrderbook(codes: List<String>)

    /**
     * KIS WS 호가 구독 해제 (H0STASP0).
     */
    fun unsubscribeOrderbook(codes: List<String>)

    /**
     * 현재 호가 구독 중인 종목코드 Set.
     */
    fun subscribedOrderbookCodes(): Set<String>
}

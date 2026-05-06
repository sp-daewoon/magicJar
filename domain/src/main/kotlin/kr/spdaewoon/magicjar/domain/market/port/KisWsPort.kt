package kr.spdaewoon.magicjar.domain.market.port

/**
 * KIS WebSocket 구독 포트 (ADR 0048 정합 — 41건 구독 한도).
 * 구현은 consumer 모듈 ws 어댑터에서 (PR-8).
 */
interface KisWsPort {
    fun subscribe(codes: List<String>)

    fun unsubscribe(codes: List<String>)

    fun subscribedCodes(): Set<String>
}

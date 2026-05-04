package com.mj.consumer.application.market

/**
 * 현재 보유 종목 코드 목록을 조회하는 포트.
 *
 * R2-A1: stub 구현만 사용 (1번 트랙 dogfooding 일시 중단).
 * R2-A3 watchlist 도입 시 DB 실 구현으로 교체.
 *
 * plan §3 T5 (2026-05-04-track2-r2-quote-ws.md).
 */
fun interface HoldingsCodeFetcher {
    /** 현재 보유(FILLED, filled_qty > 0)인 종목 코드 목록 반환. */
    fun fetchHoldingCodes(): List<String>
}

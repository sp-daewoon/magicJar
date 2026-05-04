package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/**
 * 호가창 한 단계 (매도/매수 1~10단계).
 *
 * @param price    호가
 * @param quantity 잔량 (주식수)
 */
data class OrderbookLevel(
    val price: BigDecimal,
    val quantity: Long,
)

/**
 * Kafka 토픽 `market.orderbook.v1` 메시지 — KIS WS H0UNASP0 (국내주식 실시간호가, 평문).
 *
 * @param code         KRX 6자리 종목코드
 * @param at           tick 시각 (KST)
 * @param asks         매도 1~10단계 (asks[0]=ASKP1, asks[9]=ASKP10)
 * @param bids         매수 1~10단계 (bids[0]=BIDP1, bids[9]=BIDP10)
 * @param totalAskQty  매도 총 잔량
 * @param totalBidQty  매수 총 잔량
 * @param source       "KIS_WS" | "CACHED"
 * @param stale        true = fallback 데이터
 * @param asOf         실제 데이터 시각 (stale=true 시 의미 있음)
 *
 * Refs: plan T2-R2-A2 §T10·§T11.
 */
data class OrderbookEvent(
    val code: String,
    val at: Instant,
    val asks: List<OrderbookLevel>,
    val bids: List<OrderbookLevel>,
    val totalAskQty: Long,
    val totalBidQty: Long,
    val source: String = "KIS_WS",
    val stale: Boolean = false,
    val asOf: Instant? = null,
) {
    init {
        require(asks.size == LEVELS) { "asks must have exactly $LEVELS levels, got ${asks.size}" }
        require(bids.size == LEVELS) { "bids must have exactly $LEVELS levels, got ${bids.size}" }
    }

    companion object {
        const val LEVELS: Int = 10
    }
}

package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/** Kafka 토픽 market.candle.1m.v1 메시지 스키마 */
data class CandleEvent(
    val code: String,
    val interval: String,
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val tradeCount: Long,
)

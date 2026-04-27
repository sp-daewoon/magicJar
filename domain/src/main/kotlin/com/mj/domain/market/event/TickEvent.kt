package com.mj.domain.market.event

import java.math.BigDecimal
import java.time.Instant

/** Kafka 토픽 market.tick.v1 메시지 스키마 */
data class TickEvent(
    val code: String,
    val at: Instant,
    val price: BigDecimal,
    val volume: Long,
    val source: String = "KIS_WS",
)

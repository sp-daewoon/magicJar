package com.mj.domain.common

import java.math.BigDecimal
import java.time.Instant

data class Candle(
    val code: String,
    val interval: Interval,
    val openTime: Instant,
    val open: BigDecimal,
    val high: BigDecimal,
    val low: BigDecimal,
    val close: BigDecimal,
    val volume: Long,
    val tradeCount: Long,
)

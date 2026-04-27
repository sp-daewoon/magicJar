package com.mj.domain.common

import java.math.BigDecimal
import java.time.Instant

data class Quote(
    val code: String,
    val at: Instant,
    val price: BigDecimal,
    val volume: Long,
)

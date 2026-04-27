package com.mj.domain.common

import java.math.BigDecimal
import java.time.Instant

data class Position(
    val code: String,
    val qty: Int,
    val avgPrice: BigDecimal,
    val openedAt: Instant,
)

package com.mj.domain.common

import java.math.BigDecimal

data class Metrics(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
    val sharpe: BigDecimal,
    val winRate: BigDecimal,
    val tradeCount: Int,
)

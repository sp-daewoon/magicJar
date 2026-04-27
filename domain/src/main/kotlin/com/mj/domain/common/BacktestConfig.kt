package com.mj.domain.common

import java.math.BigDecimal

data class BacktestConfig(
    val initialCapitalKrw: BigDecimal,
    // 예: 0.00015
    val feeRate: BigDecimal,
    val taxRateKospi: BigDecimal,
    val taxRateKosdaq: BigDecimal,
    val slippageBp: BigDecimal,
)

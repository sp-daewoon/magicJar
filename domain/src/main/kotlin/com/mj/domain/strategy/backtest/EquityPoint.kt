package com.mj.domain.strategy.backtest

import java.math.BigDecimal
import java.time.Instant

/**
 * 백테스트 자산 곡선 1점 (mark-to-market).
 *
 * `equity = cash + position.shares * close` (보유 봉 종가 기준).
 */
data class EquityPoint(
    val at: Instant,
    val equity: BigDecimal,
    val cash: BigDecimal,
    val positionValue: BigDecimal,
)

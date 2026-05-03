package com.mj.domain.strategy.backtest

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.Instant
import java.util.UUID

/**
 * 라운드트립 거래 (entry → exit 한 쌍).
 *
 * 백테스트 결과의 거래 단위. 미청산 포지션은 [Trade]가 아니라 [BacktestPosition]으로 표현.
 *
 * - `pnl`: 손익(원). 비용(수수료·거래세·슬리피지) 차감 후 순손익.
 * - `pnlPercent`: 비율(예: 0.05 = 5%). 진입 명목금액 대비.
 * - `reason`: 청산 사유. 신호 종료/스탑로스/시간 만료 등.
 */
data class Trade(
    val id: String = UUID.randomUUID().toString(),
    val symbol: String,
    val entryDate: Instant,
    val exitDate: Instant,
    val entryPrice: BigDecimal,
    val exitPrice: BigDecimal,
    val shares: Int,
    val pnl: BigDecimal,
    val pnlPercent: BigDecimal,
    val entryCommission: BigDecimal,
    val exitCommission: BigDecimal,
    val exitTax: BigDecimal,
    val reason: ExitReason,
) {
    val isWin: Boolean get() = pnl.signum() > 0

    val holdingBars: Int? = null

    enum class ExitReason {
        SIGNAL_EXIT,
        STOP_LOSS,
        TAKE_PROFIT,
        END_OF_DATA,
    }

    companion object {
        /**
         * Trade 생성 헬퍼. pnl·pnlPercent를 진입/청산가·수량·비용으로부터 계산.
         */
        fun create(
            symbol: String,
            entryDate: Instant,
            exitDate: Instant,
            entryPrice: BigDecimal,
            exitPrice: BigDecimal,
            shares: Int,
            entryCommission: BigDecimal,
            exitCommission: BigDecimal,
            exitTax: BigDecimal,
            reason: ExitReason = ExitReason.SIGNAL_EXIT,
            id: String = UUID.randomUUID().toString(),
        ): Trade {
            val sharesBd = BigDecimal(shares)
            val grossPnl =
                exitPrice
                    .subtract(entryPrice)
                    .multiply(sharesBd)
            val pnl =
                grossPnl
                    .subtract(entryCommission)
                    .subtract(exitCommission)
                    .subtract(exitTax)
            val notional = entryPrice.multiply(sharesBd)
            val pnlPercent =
                if (notional.signum() > 0) {
                    pnl.divide(notional, MC).setScale(8, RoundingMode.HALF_UP)
                } else {
                    BigDecimal.ZERO
                }
            return Trade(
                id = id,
                symbol = symbol,
                entryDate = entryDate,
                exitDate = exitDate,
                entryPrice = entryPrice,
                exitPrice = exitPrice,
                shares = shares,
                pnl = pnl.setScale(2, RoundingMode.HALF_UP),
                pnlPercent = pnlPercent,
                entryCommission = entryCommission.setScale(2, RoundingMode.HALF_UP),
                exitCommission = exitCommission.setScale(2, RoundingMode.HALF_UP),
                exitTax = exitTax.setScale(2, RoundingMode.HALF_UP),
                reason = reason,
            )
        }

        private val MC = MathContext(12, RoundingMode.HALF_UP)
    }
}

/**
 * 백테스트 진행 중인 포지션 (미청산 상태).
 *
 * 도메인 [com.mj.domain.common.Position]은 실거래용(현재 보유). 백테스트 내부 상태는 별도.
 */
data class BacktestPosition(
    val symbol: String,
    val entryDate: Instant,
    val entryPrice: BigDecimal,
    val shares: Int,
    val entryCommission: BigDecimal,
) {
    val notional: BigDecimal get() = entryPrice.multiply(BigDecimal(shares))

    /** 현재가 기준 평가액 (mark-to-market). */
    fun marketValue(currentPrice: BigDecimal): BigDecimal = currentPrice.multiply(BigDecimal(shares))

    /** 현재가 기준 미실현 손익(비용 미차감). */
    fun unrealizedPnl(currentPrice: BigDecimal): BigDecimal = currentPrice.subtract(entryPrice).multiply(BigDecimal(shares))
}

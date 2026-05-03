package com.mj.domain.strategy.backtest

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 백테스트 성과 지표 (master-architecture §2 + 책 예제 case 정합).
 *
 * 모든 값은 [BigDecimal] (floating point 금지).
 *
 * - `totalReturn`: 누적 수익률 = (terminal - initial) / initial (예: 0.123 = +12.3%).
 * - `cagr`: 연환산 복리 수익률.
 * - `mdd`: Max Drawdown (음수, 예: -0.082 = -8.2%).
 * - `sharpe`: 일별 수익률 기준 샤프 비율, √252 연환산.
 * - `winRate`: 승률 = 이익 trade / 총 trade (예: 0.55 = 55%).
 * - `profitFactor`: 총 이익 / 총 손실의 절대값 (≥ 1.0이 흑자).
 * - `maxConsecutiveLosses`: 최대 연속 손실 trade 수.
 * - `avgWin` / `avgLoss`: 평균 이익·평균 손실(절대값).
 * - `tradeCount`: 종결된 trade 수.
 */
data class PerformanceMetrics(
    val totalReturn: BigDecimal,
    val cagr: BigDecimal,
    val mdd: BigDecimal,
    val sharpe: BigDecimal,
    val winRate: BigDecimal,
    val profitFactor: BigDecimal,
    val maxConsecutiveLosses: Int,
    val avgWin: BigDecimal,
    val avgLoss: BigDecimal,
    val tradeCount: Int,
) {
    companion object {
        val EMPTY =
            PerformanceMetrics(
                totalReturn = BigDecimal.ZERO,
                cagr = BigDecimal.ZERO,
                mdd = BigDecimal.ZERO,
                sharpe = BigDecimal.ZERO,
                winRate = BigDecimal.ZERO,
                profitFactor = BigDecimal.ZERO,
                maxConsecutiveLosses = 0,
                avgWin = BigDecimal.ZERO,
                avgLoss = BigDecimal.ZERO,
                tradeCount = 0,
            )
    }
}

/**
 * 성과 지표 계산기.
 *
 * 입력: equity curve(시작 equity 포함) + 종결된 trade 목록.
 * 출력: [PerformanceMetrics].
 *
 * 결정론: 동일 입력 → 동일 출력. 난수 사용 안 함.
 */
object PerformanceMetricsCalculator {
    private val MC = MathContext(12, RoundingMode.HALF_UP)
    private val SQRT_252: BigDecimal = BigDecimal("252").sqrt(MC)
    private val ZERO = BigDecimal.ZERO
    private val ONE = BigDecimal.ONE

    /**
     * @param equityCurve 일별 자산곡선(시작값 포함, 길이 ≥ 1)
     * @param trades 종결된 trade 목록 (미청산은 제외)
     * @param years 기간(연 단위 BigDecimal). CAGR 계산용. 0 또는 음수면 CAGR=0.
     */
    fun calculate(
        equityCurve: List<BigDecimal>,
        trades: List<Trade>,
        years: BigDecimal,
    ): PerformanceMetrics {
        require(equityCurve.isNotEmpty()) { "equityCurve must not be empty" }
        val initial = equityCurve.first()
        val terminal = equityCurve.last()
        val totalReturn =
            if (initial.signum() == 0) {
                ZERO
            } else {
                terminal.subtract(initial).divide(initial, MC)
            }
        val cagr = computeCagr(initial, terminal, years)
        val mdd = computeMdd(equityCurve)
        val daily = computeDailyReturns(equityCurve)
        val sharpe = computeSharpe(daily)

        val pnls = trades.map { it.pnl }
        val wins = pnls.filter { it.signum() > 0 }
        val losses = pnls.filter { it.signum() < 0 }
        val winRate =
            if (trades.isEmpty()) {
                ZERO
            } else {
                BigDecimal(wins.size).divide(BigDecimal(trades.size), MC)
            }
        val grossProfit = wins.fold(ZERO) { acc, v -> acc.add(v) }
        val grossLoss = losses.fold(ZERO) { acc, v -> acc.add(v) }.abs()
        val profitFactor =
            if (grossLoss.signum() == 0) {
                if (grossProfit.signum() > 0) BigDecimal("999.999999") else ZERO
            } else {
                grossProfit.divide(grossLoss, MC)
            }
        val avgWin =
            if (wins.isEmpty()) ZERO else grossProfit.divide(BigDecimal(wins.size), MC)
        val avgLoss =
            if (losses.isEmpty()) ZERO else grossLoss.divide(BigDecimal(losses.size), MC)
        val maxConsecutiveLosses = computeMaxConsecutiveLosses(trades)

        return PerformanceMetrics(
            totalReturn = totalReturn.setScale(6, RoundingMode.HALF_UP),
            cagr = cagr.setScale(6, RoundingMode.HALF_UP),
            mdd = mdd.setScale(6, RoundingMode.HALF_UP),
            sharpe = sharpe.setScale(6, RoundingMode.HALF_UP),
            winRate = winRate.setScale(6, RoundingMode.HALF_UP),
            profitFactor = profitFactor.setScale(6, RoundingMode.HALF_UP),
            maxConsecutiveLosses = maxConsecutiveLosses,
            avgWin = avgWin.setScale(2, RoundingMode.HALF_UP),
            avgLoss = avgLoss.setScale(2, RoundingMode.HALF_UP),
            tradeCount = trades.size,
        )
    }

    private fun computeCagr(
        initial: BigDecimal,
        terminal: BigDecimal,
        years: BigDecimal,
    ): BigDecimal {
        if (initial.signum() <= 0 || years.signum() <= 0) return ZERO
        if (terminal.signum() <= 0) return BigDecimal("-1")
        val ratio = terminal.divide(initial, MC).toDouble()
        val yearsD = years.toDouble()
        if (ratio <= 0.0 || yearsD <= 0.0) return ZERO
        val cagrD = Math.pow(ratio, 1.0 / yearsD) - 1.0
        return BigDecimal(cagrD, MC)
    }

    private fun computeMdd(equity: List<BigDecimal>): BigDecimal {
        var peak = equity.first()
        var maxDrawdown = ZERO
        for (v in equity) {
            if (v > peak) peak = v
            if (peak.signum() == 0) continue
            val dd = v.subtract(peak).divide(peak, MC) // negative or zero
            if (dd < maxDrawdown) maxDrawdown = dd
        }
        return maxDrawdown
    }

    private fun computeDailyReturns(equity: List<BigDecimal>): List<BigDecimal> {
        if (equity.size < 2) return emptyList()
        val out = ArrayList<BigDecimal>(equity.size - 1)
        for (i in 1 until equity.size) {
            val prev = equity[i - 1]
            if (prev.signum() == 0) {
                out += ZERO
            } else {
                out += equity[i].subtract(prev).divide(prev, MC)
            }
        }
        return out
    }

    private fun computeSharpe(daily: List<BigDecimal>): BigDecimal {
        if (daily.isEmpty()) return ZERO
        val n = BigDecimal(daily.size)
        var sum = ZERO
        for (v in daily) sum = sum.add(v)
        val mean = sum.divide(n, MC)
        var sqDiff = ZERO
        for (v in daily) {
            val diff = v.subtract(mean)
            sqDiff = sqDiff.add(diff.multiply(diff, MC))
        }
        val denom = if (daily.size > 1) BigDecimal(daily.size - 1) else ONE
        val variance = sqDiff.divide(denom, MC)
        if (variance.signum() <= 0) return ZERO
        val stddev = variance.sqrt(MC)
        if (stddev.signum() == 0) return ZERO
        return mean.divide(stddev, MC).multiply(SQRT_252, MC)
    }

    private fun computeMaxConsecutiveLosses(trades: List<Trade>): Int {
        var current = 0
        var max = 0
        for (t in trades) {
            if (t.pnl.signum() < 0) {
                current++
                if (current > max) max = current
            } else {
                current = 0
            }
        }
        return max
    }
}

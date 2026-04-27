package com.mj.domain.strategy.backtest

import com.mj.domain.common.Metrics
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * 백테스트 성과 지표 계산기.
 *
 * 입력:
 * - `equityCurve`: 일별 종가 기준 포트폴리오 평가액(시작가 포함). 길이 ≥ 1.
 * - `roundTrips`: 종결된 라운드트립 손익(매도 시 실현 손익). 미청산 포지션은 비포함.
 * - `from`, `to`: 백테스트 기간(연수 계산용).
 *
 * 모든 계산은 BigDecimal. 샤프는 일별 수익률에서 표본 표준편차 사용, 연환산 √252.
 * MDD는 0..1 사이 음수(예: -0.12).
 */
object MetricsCalculator {
    private val MC = MathContext(12, RoundingMode.HALF_UP)
    private val SQRT_252: BigDecimal = BigDecimal("252").sqrt(MC)
    private val ZERO = BigDecimal.ZERO
    private val ONE = BigDecimal.ONE

    fun calculate(
        equityCurve: List<BigDecimal>,
        roundTrips: List<BigDecimal>,
        from: LocalDate,
        to: LocalDate,
    ): Metrics {
        require(equityCurve.isNotEmpty()) { "equityCurve must not be empty" }
        val initial = equityCurve.first()
        val terminal = equityCurve.last()
        val totalReturn =
            if (initial.signum() == 0) ZERO else terminal.subtract(initial).divide(initial, MC)
        val years = computeYears(from, to)
        val cagr =
            if (years.signum() == 0 || initial.signum() == 0) {
                ZERO
            } else {
                computeCagr(initial, terminal, years)
            }
        val mdd = computeMdd(equityCurve)
        val daily = computeDailyReturns(equityCurve)
        val sharpe = computeSharpe(daily)
        val winRate =
            if (roundTrips.isEmpty()) {
                ZERO
            } else {
                val wins = roundTrips.count { it.signum() > 0 }
                BigDecimal(wins).divide(BigDecimal(roundTrips.size), MC)
            }
        return Metrics(
            totalReturn = totalReturn.setScale(6, RoundingMode.HALF_UP),
            cagr = cagr.setScale(6, RoundingMode.HALF_UP),
            mdd = mdd.setScale(6, RoundingMode.HALF_UP),
            sharpe = sharpe.setScale(6, RoundingMode.HALF_UP),
            winRate = winRate.setScale(6, RoundingMode.HALF_UP),
            tradeCount = roundTrips.size,
        )
    }

    private fun computeYears(
        from: LocalDate,
        to: LocalDate,
    ): BigDecimal {
        val days = ChronoUnit.DAYS.between(from, to).coerceAtLeast(1)
        return BigDecimal(days).divide(BigDecimal("365.25"), MC)
    }

    private fun computeCagr(
        initial: BigDecimal,
        terminal: BigDecimal,
        years: BigDecimal,
    ): BigDecimal {
        if (terminal.signum() <= 0) return BigDecimal("-1")
        // CAGR = (terminal/initial)^(1/years) - 1
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
            val dd = v.subtract(peak).divide(peak, MC) // negative
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
        // sample stddev (n-1)
        val denom = if (daily.size > 1) BigDecimal(daily.size - 1) else ONE
        val variance = sqDiff.divide(denom, MC)
        if (variance.signum() <= 0) return ZERO
        val stddev = variance.sqrt(MC)
        if (stddev.signum() == 0) return ZERO
        return mean.divide(stddev, MC).multiply(SQRT_252, MC)
    }
}

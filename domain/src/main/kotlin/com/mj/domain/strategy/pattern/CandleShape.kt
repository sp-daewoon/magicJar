package com.mj.domain.strategy.pattern

import com.mj.domain.common.Candle
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 캔들 형태 보조 계산. 모든 가격 비교는 BigDecimal로 수행 (코드 컨벤션 §4.1).
 *
 * 이 객체는 인식기 내부 전용이며 외부에 노출하지 않는다.
 */
internal object CandleShape {
    /** 12자리 정밀도, RSI/볼린저 등 기존 indicator 모듈과 동일 */
    val MC: MathContext = MathContext(12, RoundingMode.HALF_UP)
    private val TWO = BigDecimal("2")
    private val HALF = BigDecimal("0.5")

    fun bodyAbs(c: Candle): BigDecimal = (c.close - c.open).abs()

    fun range(c: Candle): BigDecimal = c.high - c.low

    fun upperShadow(c: Candle): BigDecimal = c.high - maxOf(c.open, c.close)

    fun lowerShadow(c: Candle): BigDecimal = minOf(c.open, c.close) - c.low

    fun isBullBody(c: Candle): Boolean = c.close > c.open

    fun isBearBody(c: Candle): Boolean = c.close < c.open

    fun bodyMid(c: Candle): BigDecimal = (c.open + c.close).divide(TWO, MC)

    /** 몸통/range 비율. range=0이면 0 반환(전체가 도지/평탄). */
    fun bodyRatio(c: Candle): BigDecimal {
        val r = range(c)
        if (r.signum() == 0) return BigDecimal.ZERO
        return bodyAbs(c).divide(r, MC)
    }

    /** "거의 같다" — 두 가격의 절대차가 기준 캔들 range의 epsilon 비율 이하. range=0이면 동일가일 때만 true */
    fun nearlyEqual(
        a: BigDecimal,
        b: BigDecimal,
        ref: BigDecimal,
        epsilon: Double = 0.05,
    ): Boolean {
        val diff = (a - b).abs()
        if (ref.signum() == 0) return diff.signum() == 0
        val eps = ref.multiply(BigDecimal(epsilon), MC)
        return diff.compareTo(eps) <= 0
    }

    /** 도지 판정 — 몸통이 range의 epsilon 이하 */
    fun isDoji(
        c: Candle,
        epsilon: Double = 0.10,
    ): Boolean {
        val r = range(c)
        if (r.signum() == 0) return true
        return bodyAbs(c).compareTo(r.multiply(BigDecimal(epsilon), MC)) <= 0
    }

    /** 캔들 a의 몸통이 캔들 b의 몸통을 완전히 포함(engulfing) */
    fun bodyEngulfs(
        outer: Candle,
        inner: Candle,
    ): Boolean {
        val outerLow = minOf(outer.open, outer.close)
        val outerHigh = maxOf(outer.open, outer.close)
        val innerLow = minOf(inner.open, inner.close)
        val innerHigh = maxOf(inner.open, inner.close)
        return outerLow <= innerLow && outerHigh >= innerHigh
    }

    /** 캔들 c가 long body인지 (몸통이 range의 절반 이상). 마루보주/큰 봉 판정용 */
    fun isLongBody(c: Candle): Boolean = bodyRatio(c).compareTo(HALF) >= 0
}

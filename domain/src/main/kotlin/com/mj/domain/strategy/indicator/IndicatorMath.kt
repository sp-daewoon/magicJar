package com.mj.domain.strategy.indicator

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/**
 * 지표 계산 공용 상수/유틸. 모든 BigDecimal 연산은 동일한 MathContext를 쓴다.
 * Double 사용 금지(이슈 추적성 + 정밀도 보존).
 */
internal object IndicatorMath {
    /** 12자리 정밀도. KRX 가격은 정수원이지만 비율·평균 계산 안정성을 위함. */
    val MC: MathContext = MathContext(12, RoundingMode.HALF_UP)

    val TWO: BigDecimal = BigDecimal("2")
    val HUNDRED: BigDecimal = BigDecimal("100")

    fun BigDecimal.divSafe(divisor: BigDecimal): BigDecimal? = if (divisor.signum() == 0) null else this.divide(divisor, MC)

    fun sqrt(value: BigDecimal): BigDecimal {
        if (value.signum() <= 0) return BigDecimal.ZERO
        return value.sqrt(MC)
    }
}

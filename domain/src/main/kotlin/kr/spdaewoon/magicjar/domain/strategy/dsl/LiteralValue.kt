package kr.spdaewoon.magicjar.domain.strategy.dsl

import java.math.BigDecimal

/**
 * DSL 리터럴 값 노드 — KIS raw parser.py L218 Value 1:1 port.
 *
 * KIS raw name: Value → LiteralValue (rename — Kotlin Value 키워드 충돌 회피).
 * Source: parser.py:L218 Value dataclass
 *
 * @param value 수치 값 (BigDecimal — KIS Python float IEEE 754 double 정밀도 보장)
 * @param isPercent % 단위 여부 (raw L218 is_percent 정합)
 */
data class LiteralValue(
    val value: BigDecimal,
    val isPercent: Boolean = false,
) : DslNode() {
    /**
     * isPercent=true 시 / 100 변환 — KIS raw parser.py L228 Value.normalized 정합.
     *
     * Source: parser.py:L228
     */
    fun normalized(): BigDecimal = if (isPercent) value.divide(BigDecimal(100)) else value
}

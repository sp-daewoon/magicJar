package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * KIS Strategy Builder DSL ArithmeticExpr.
 *
 * Source: parser.py:L235 · License: MIT
 * Catalog: _workspace/specs/pr-35-kis-dsl-raw-verification.md §2-2
 *
 * `volume_ma(20) * 1.5` → ArithmeticExpr(IndicatorRef("volume_ma",[20]), MUL, LiteralValue(1.5)).
 *
 * 우선순위: `* /` > `+ -` (parser.py:L450-L470 정합).
 */
data class ArithmeticExpr(
    val left: DslNode,
    val operator: ArithmeticOperator,
    val right: DslNode,
) : DslNode()

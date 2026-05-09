package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 비교·교차 연산자 — KIS raw parser.py L34 Operator 1:1 port.
 *
 * Source: parser.py:L34 Operator enum
 * KIS raw name: Operator (rename 없음)
 */
enum class Operator(
    val symbol: String,
) {
    GT(">"),
    LT("<"),
    GTE(">="),
    LTE("<="),
    EQ("=="),
    NEQ("!="),
    CROSSES_ABOVE("crosses_above"),
    CROSSES_BELOW("crosses_below"),
    BREAKS("breaks"),
}

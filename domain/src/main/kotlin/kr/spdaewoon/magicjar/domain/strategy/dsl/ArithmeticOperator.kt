package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 산술 연산자 — KIS raw parser.py L52 ArithmeticOperator 1:1 port.
 *
 * Source: parser.py:L52 ArithmeticOperator enum
 * KIS raw name: ArithmeticOperator (rename 없음)
 */
enum class ArithmeticOperator(
    val symbol: String,
) {
    ADD("+"),
    SUB("-"),
    MUL("*"),
    DIV("/"),
}

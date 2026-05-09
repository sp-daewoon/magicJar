package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 산술 표현식 노드 — KIS raw parser.py L235 ArithmeticExpr 1:1 port.
 *
 * Source: parser.py:L235 ArithmeticExpr dataclass
 *
 * @param left 왼쪽 피연산자
 * @param operator 산술 연산자 (ADD·SUB·MUL·DIV)
 * @param right 오른쪽 피연산자
 */
data class ArithmeticExpr(
    val left: DslNode,
    val operator: ArithmeticOperator,
    val right: DslNode,
) : DslNode()

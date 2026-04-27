package com.mj.domain.strategy.dsl

import java.math.BigDecimal

/**
 * 조건식 DSL의 추상 구문 트리.
 *
 * Bool 노드는 boolean을 반환하고, Num 노드는 BigDecimal을 반환한다.
 * 비교 연산자(Compare)만이 Num → Bool 변환점이다.
 */
internal sealed interface BoolNode

internal sealed interface NumNode

internal data class And(
    val left: BoolNode,
    val right: BoolNode,
) : BoolNode

internal data class Or(
    val left: BoolNode,
    val right: BoolNode,
) : BoolNode

internal data class Not(
    val inner: BoolNode,
) : BoolNode

internal data class Compare(
    val left: NumNode,
    val op: TokenType,
    val right: NumNode,
) : BoolNode

internal data class NumLiteral(
    val value: BigDecimal,
) : NumNode

/** 식별자 호출. 인자가 있으면 함수(SMA, RSI ...), 없으면 컬럼/심볼(CLOSE, VOLUME ...). */
internal data class Call(
    val name: String,
    val args: List<NumNode>,
) : NumNode

internal data class BinOp(
    val left: NumNode,
    val op: TokenType,
    val right: NumNode,
) : NumNode

internal data class Neg(
    val inner: NumNode,
) : NumNode

class DslParseException(
    message: String,
) : RuntimeException(message)

class DslEvaluationException(
    message: String,
) : RuntimeException(message)

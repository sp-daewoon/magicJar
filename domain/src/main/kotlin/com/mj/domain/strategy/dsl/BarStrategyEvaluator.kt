package com.mj.domain.strategy.dsl

/**
 * 백테스트/실시간 봉 평가기. 캔들 + currentIndex 컨텍스트로 지표 직접 계산.
 *
 * `compile`로 노드 캐싱을 명시적으로 한 번 해두고, 같은 식을 매 봉마다 [evaluate]에 넘기는 패턴 권장.
 */
class BarStrategyEvaluator {
    private val evaluator = Evaluator()

    fun compile(expr: String): CompiledExpression {
        val tokens = Lexer(expr).tokenize()
        val node = Parser(tokens).parse()
        return CompiledExpression(node, evaluator)
    }

    fun evaluate(
        expr: String,
        context: BarEvaluationContext,
    ): Boolean = compile(expr).evaluate(context)
}

class CompiledExpression internal constructor(
    private val node: BoolNode,
    private val evaluator: Evaluator,
) {
    fun evaluate(context: BarEvaluationContext): Boolean = evaluator.evaluate(node, context)
}

package kr.spdaewoon.magicjar.strategy.dsl

import kr.spdaewoon.magicjar.core.port.EvaluationContext
import kr.spdaewoon.magicjar.core.port.StrategyEvaluator
import org.springframework.stereotype.Component

/**
 * core.port.StrategyEvaluator 구현. flat values map만 가지고 평가하는 모드.
 *
 * 백테스트 엔진은 직접 [BarStrategyEvaluator]를 사용해 캔들 컨텍스트와 함께 평가한다.
 */
@Component
class DslStrategyEvaluator : StrategyEvaluator {
    private val cache = HashMap<String, BoolNode>()
    private val evaluator = Evaluator()

    override fun evaluate(
        expr: String,
        context: EvaluationContext,
    ): Boolean {
        val node = parseCached(expr)
        return evaluator.evaluateFlat(node, context.values)
    }

    @Synchronized
    private fun parseCached(expr: String): BoolNode =
        cache.getOrPut(expr) {
            val tokens = Lexer(expr).tokenize()
            Parser(tokens).parse()
        }
}

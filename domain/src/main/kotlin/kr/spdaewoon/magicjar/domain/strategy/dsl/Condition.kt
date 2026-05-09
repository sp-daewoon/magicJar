package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 단일 조건 노드 — KIS raw parser.py L251 Condition 1:1 port.
 *
 * Source: parser.py:L251 Condition dataclass
 *
 * @param left 왼쪽 피연산자
 * @param operator 비교 연산자
 * @param right 오른쪽 피연산자
 * @param conditionType 조건 유형 (CROSSOVER·THRESHOLD·BREAKOUT·PATTERN·COMPOSITE)
 */
data class Condition(
    val left: DslNode,
    val operator: Operator,
    val right: DslNode,
    val conditionType: ConditionType = ConditionType.THRESHOLD,
) : DslNode() {
    /**
     * 평가에 필요한 최소 캔들 수 — KIS raw parser.py L283-L296 get_required_days() port.
     *
     * Source: parser.py:L283-L296
     * 최소 10 보정(교차 판별 시 이전 값 비교 필요).
     */
    fun requiredDays(): Int {
        fun extract(node: DslNode): Int =
            when (node) {
                is IndicatorRef -> node.requiredDays()
                is ArithmeticExpr -> maxOf(extract(node.left), extract(node.right))
                else -> 0
            }
        return maxOf(10, extract(left) + 10, extract(right) + 10)
    }
}

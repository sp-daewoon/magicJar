package kr.spdaewoon.magicjar.domain.strategy.dsl

/**
 * DSL 복합 조건 노드 (AND/OR) — KIS raw parser.py L283 CompositeCondition 1:1 port.
 *
 * Source: parser.py:L283 CompositeCondition dataclass
 *
 * @param left 왼쪽 조건
 * @param operator 논리 연산자 (AND/OR)
 * @param right 오른쪽 조건
 */
data class CompositeCondition(
    val left: DslNode,
    val operator: LogicalOperator,
    val right: DslNode,
) : DslNode() {
    /**
     * 평가에 필요한 최소 캔들 수 — 양쪽 서브 조건 max.
     */
    fun requiredDays(): Int =
        when {
            left is Condition && right is Condition -> maxOf(left.requiredDays(), right.requiredDays())
            left is CompositeCondition && right is Condition -> maxOf(left.requiredDays(), right.requiredDays())
            left is Condition && right is CompositeCondition -> maxOf(left.requiredDays(), right.requiredDays())
            left is CompositeCondition && right is CompositeCondition ->
                maxOf(left.requiredDays(), right.requiredDays())
            else -> 10
        }
}

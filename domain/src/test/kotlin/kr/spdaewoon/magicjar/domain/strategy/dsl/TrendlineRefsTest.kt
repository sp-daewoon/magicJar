package kr.spdaewoon.magicjar.domain.strategy.dsl

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * [TrendlineRefs] 순수함수 — 참조 id 추출 (ADR 0140 §3-3, spec R2 참조 동기화 근거).
 */
class TrendlineRefsTest {
    @Test
    fun `단일 Condition 우변 TrendlineRef 추출`() {
        val ast =
            Condition(
                left = IndicatorRef("close", emptyList()),
                operator = Operator.CROSSES_ABOVE,
                right = TrendlineRef(123L),
                conditionType = ConditionType.CROSSOVER,
            )
        assertEquals(setOf(123L), TrendlineRefs.collect(ast))
    }

    @Test
    fun `CompositeCondition + ArithmeticExpr 중첩에서 여러 id 추출·중복 제거`() {
        // (close > trendline(1)) AND (close < trendline(2) + trendline(1))
        val left =
            Condition(IndicatorRef("close", emptyList()), Operator.GT, TrendlineRef(1L))
        val right =
            Condition(
                IndicatorRef("close", emptyList()),
                Operator.LT,
                ArithmeticExpr(TrendlineRef(2L), ArithmeticOperator.ADD, TrendlineRef(1L)),
            )
        val ast = CompositeCondition(left, LogicalOperator.AND, right)
        assertEquals(setOf(1L, 2L), TrendlineRefs.collect(ast))
    }

    @Test
    fun `TrendlineRef 없는 순수 indicator DSL은 빈 집합`() {
        val ast =
            Condition(
                IndicatorRef("ma", listOf("5")),
                Operator.CROSSES_ABOVE,
                IndicatorRef("ma", listOf("20")),
                ConditionType.CROSSOVER,
            )
        assertEquals(emptySet<Long>(), TrendlineRefs.collect(ast))
    }

    @Test
    fun `collectAll — entry ∪ exit, exit null 안전`() {
        val entry = Condition(IndicatorRef("close", emptyList()), Operator.GT, TrendlineRef(10L))
        val exit = Condition(IndicatorRef("close", emptyList()), Operator.LT, TrendlineRef(20L))
        assertEquals(setOf(10L, 20L), TrendlineRefs.collectAll(entry, exit))
        assertEquals(setOf(10L), TrendlineRefs.collectAll(entry, null))
        assertEquals(emptySet<Long>(), TrendlineRefs.collectAll(null, null))
    }
}

package com.mj.api.infrastructure.dsl

import com.mj.domain.strategy.dsl.Condition
import com.mj.domain.strategy.dsl.ConditionOperator
import com.mj.domain.strategy.dsl.IndicatorOperand
import com.mj.domain.strategy.dsl.LogicalGroup
import com.mj.domain.strategy.dsl.LogicalOp
import com.mj.domain.strategy.dsl.PatternCondition
import com.mj.domain.strategy.dsl.PriceField
import com.mj.domain.strategy.dsl.PriceOperand
import com.mj.domain.strategy.dsl.StrategyDslValidator
import com.mj.domain.strategy.dsl.ValidationResult
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

/**
 * StrategyDslParser yaml ⇄ 도메인 round-trip 테스트.
 */
class StrategyDslParserTest {
    private val parser = StrategyDslParser()

    @Test
    fun `parse magicjar yaml — golden cross`() {
        val yaml =
            """
            version: "1.0"
            metadata:
              name: golden_cross_5_20
              description: 5/20 MA cross
              tags: [trend_following, ma_cross]
            strategy:
              id: golden_cross
              indicators:
                - id: ma
                  alias: ma5
                  params: { period: 5 }
                - id: ma
                  alias: ma20
                  params: { period: 20 }
              entry:
                logic: AND
                conditions:
                  - lhs: { alias: ma5 }
                    op: cross_above
                    rhs: { alias: ma20 }
              exit:
                logic: AND
                conditions:
                  - lhs: { alias: ma5 }
                    op: cross_below
                    rhs: { alias: ma20 }
            """.trimIndent()

        val dsl = parser.parse(yaml)
        dsl.metadata.name shouldBe "golden_cross_5_20"
        dsl.strategy.id shouldBe "golden_cross"
        dsl.strategy.indicators.size shouldBe 2
        dsl.strategy.indicators[0].alias shouldBe "ma5"
        dsl.strategy.indicators[0].params["period"] shouldBe 5

        val entryNode =
            dsl.strategy.entry.nodes
                .first()
        check(entryNode is Condition)
        entryNode.operator shouldBe ConditionOperator.CROSS_ABOVE
        check(entryNode.lhs is IndicatorOperand)
        (entryNode.lhs as IndicatorOperand).alias shouldBe "ma5"
        check(entryNode.rhs is IndicatorOperand)
        (entryNode.rhs as IndicatorOperand).alias shouldBe "ma20"

        // validator 통과
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `parse KIS-style top-level name + entry without metadata block`() {
        val yaml =
            """
            name: rsi_simple
            indicators:
              - id: rsi
                alias: rsi14
                params: { period: 14 }
            entry:
              logic: AND
              conditions:
                - lhs: { alias: rsi14 }
                  op: less_than
                  rhs: { value: 30 }
            exit:
              logic: AND
              conditions:
                - lhs: { alias: rsi14 }
                  op: greater_than
                  rhs: { value: 70 }
            """.trimIndent()

        val dsl = parser.parse(yaml)
        dsl.metadata.name shouldBe "rsi_simple"
        dsl.strategy.id shouldBe "rsi_simple"
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `pattern condition parses correctly`() {
        val yaml =
            """
            metadata:
              name: pattern_strategy
            strategy:
              id: pattern_strategy
              indicators: []
              patterns:
                - code: morning_star
              entry:
                logic: AND
                conditions:
                  - pattern: morning_star
                    expected: true
              exit:
                logic: AND
                conditions:
                  - pattern: evening_star
                    expected: true
            """.trimIndent()

        val dsl = parser.parse(yaml)
        val entryNode =
            dsl.strategy.entry.nodes
                .first()
        check(entryNode is PatternCondition)
        entryNode.code shouldBe "morning_star"
        entryNode.expected shouldBe true
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `price operand string shortcut`() {
        // string "close" 가 PriceOperand로 매핑되는지
        val yaml =
            """
            metadata:
              name: price_check
            strategy:
              id: price_check
              indicators:
                - id: ma
                  alias: ma20
                  params: { period: 20 }
              entry:
                logic: AND
                conditions:
                  - lhs: close
                    op: greater_than
                    rhs: { alias: ma20 }
              exit:
                logic: AND
                conditions:
                  - lhs: close
                    op: less_than
                    rhs: { alias: ma20 }
            """.trimIndent()

        val dsl = parser.parse(yaml)
        val node =
            dsl.strategy.entry.nodes
                .first()
        check(node is Condition)
        check(node.lhs is PriceOperand)
        (node.lhs as PriceOperand).field shouldBe PriceField.CLOSE
    }

    @Test
    fun `round-trip — parse then serialize then parse keeps shape`() {
        val original =
            """
            version: "1.0"
            metadata:
              name: rt_test
              description: round-trip
              tags: [a, b]
            strategy:
              id: rt
              indicators:
                - id: rsi
                  alias: rsi14
                  params: { period: 14 }
              entry:
                logic: AND
                conditions:
                  - lhs: { alias: rsi14 }
                    op: less_than
                    rhs: { value: 30 }
              exit:
                logic: AND
                conditions:
                  - lhs: { alias: rsi14 }
                    op: greater_than
                    rhs: { value: 70 }
            risk:
              stop_loss:
                enabled: true
                percent: 0.05
            """.trimIndent()

        val dsl1 = parser.parse(original)
        val yaml2 = parser.serialize(dsl1)
        val dsl2 = parser.parse(yaml2)
        dsl2.metadata.name shouldBe dsl1.metadata.name
        dsl2.strategy.id shouldBe dsl1.strategy.id
        dsl2.strategy.indicators.size shouldBe dsl1.strategy.indicators.size
        dsl2.strategy.entry.nodes.size shouldBe dsl1.strategy.entry.nodes.size
        dsl2.risk
            ?.stopLoss
            ?.percent
            ?.compareTo(dsl1.risk!!.stopLoss!!.percent) shouldBe 0
    }

    @Test
    fun `unknown operator throws DslParseException`() {
        val yaml =
            """
            metadata:
              name: bad
            strategy:
              id: bad
              indicators:
                - id: rsi
                  alias: rsi14
                  params: { period: 14 }
              entry:
                logic: AND
                conditions:
                  - lhs: { alias: rsi14 }
                    op: between
                    rhs: { value: 30 }
              exit:
                logic: AND
                conditions:
                  - lhs: { alias: rsi14 }
                    op: greater_than
                    rhs: { value: 70 }
            """.trimIndent()

        shouldThrow<DslParseException> { parser.parse(yaml) }
    }

    @Test
    fun `blank yaml throws`() {
        shouldThrow<DslParseException> { parser.parse("") }
    }

    @Test
    fun `nested logical groups roundtrip`() {
        val yaml =
            """
            metadata:
              name: nested
            strategy:
              id: nested
              indicators:
                - id: rsi
                  alias: rsi14
                  params: { period: 14 }
                - id: ma
                  alias: ma20
                  params: { period: 20 }
              entry:
                logic: AND
                conditions:
                  - logic: OR
                    conditions:
                      - lhs: { alias: rsi14 }
                        op: less_than
                        rhs: { value: 30 }
                      - lhs: close
                        op: greater_than
                        rhs: { alias: ma20 }
                  - lhs: volume
                    op: greater_than
                    rhs: { value: 1000 }
              exit:
                logic: AND
                conditions:
                  - lhs: close
                    op: less_than
                    rhs: { alias: ma20 }
            """.trimIndent()

        val dsl = parser.parse(yaml)
        val rootGroup = dsl.strategy.entry
        rootGroup.nodes.size shouldBe 2
        check(rootGroup.nodes[0] is LogicalGroup)
        (rootGroup.nodes[0] as LogicalGroup).logic shouldBe LogicalOp.OR
        (rootGroup.nodes[0] as LogicalGroup).nodes.size shouldBe 2
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }
}

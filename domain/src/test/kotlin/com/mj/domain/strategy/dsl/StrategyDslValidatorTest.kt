package com.mj.domain.strategy.dsl

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * StrategyDslValidator 검증 테스트 — 12 검증 항목 + 정상 케이스.
 */
class StrategyDslValidatorTest {
    @Test
    fun `valid simple strategy passes`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14", params = mapOf("period" to 14))),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes =
                            listOf(
                                Condition(
                                    lhs = IndicatorOperand(alias = "rsi14"),
                                    operator = ConditionOperator.LESS_THAN,
                                    rhs = ConstantOperand(BigDecimal("30")),
                                ),
                            ),
                    ),
            )
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `unsupported version returns UNSUPPORTED_VERSION error`() {
        val dsl =
            buildDsl(
                version = "9.9",
                indicators = listOf(IndicatorRef(id = "rsi")),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "UNSUPPORTED_VERSION"
    }

    @Test
    fun `unknown indicator id returns UNKNOWN_INDICATOR error`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "unknown_made_up_indicator")),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "UNKNOWN_INDICATOR"
    }

    @Test
    fun `unknown pattern code returns UNKNOWN_PATTERN error`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi")),
                patterns = listOf(PatternRef(code = "made_up_pattern")),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "UNKNOWN_PATTERN"
    }

    @Test
    fun `duplicate alias returns DUPLICATE_ALIAS error`() {
        val dsl =
            buildDsl(
                indicators =
                    listOf(
                        IndicatorRef(id = "rsi", alias = "x"),
                        IndicatorRef(id = "ma", alias = "x"),
                    ),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "DUPLICATE_ALIAS"
    }

    @Test
    fun `unknown alias in operand returns UNKNOWN_INDICATOR_ALIAS`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes =
                            listOf(
                                Condition(
                                    lhs = IndicatorOperand(alias = "totally_unknown_alias"),
                                    operator = ConditionOperator.GREATER_THAN,
                                    rhs = ConstantOperand(BigDecimal.ONE),
                                ),
                            ),
                    ),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "UNKNOWN_INDICATOR_ALIAS"
    }

    @Test
    fun `cross_above RSI crossing 50 — series LHS + constant RHS — passes`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes =
                            listOf(
                                Condition(
                                    lhs = IndicatorOperand(alias = "rsi14"),
                                    operator = ConditionOperator.CROSS_ABOVE,
                                    rhs = ConstantOperand(BigDecimal("50")),
                                ),
                            ),
                    ),
            )
        // RHS 상수는 허용 — 일반적인 RSI/CCI 등 cross 50 패턴
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `cross_above with constant LHS returns CROSS_REQUIRES_SERIES_LHS`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes =
                            listOf(
                                Condition(
                                    lhs = ConstantOperand(BigDecimal("50")),
                                    operator = ConditionOperator.CROSS_ABOVE,
                                    rhs = IndicatorOperand(alias = "rsi14"),
                                ),
                            ),
                    ),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "CROSS_REQUIRES_SERIES_LHS"
    }

    @Test
    fun `nested groups within depth limit pass`() {
        // depth 5 (root + 4 nested)
        val nested =
            buildNestedGroup(depth = 5, leafIndicator = "rsi14")
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry = nested,
            )
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `nested groups exceeding depth limit return GROUP_TOO_DEEP`() {
        val nested = buildNestedGroup(depth = 7, leafIndicator = "rsi14")
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry = nested,
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "GROUP_TOO_DEEP"
    }

    @Test
    fun `pattern condition with declared pattern passes`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                patterns = listOf(PatternRef(code = "morning_star")),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes = listOf(PatternCondition(code = "morning_star")),
                    ),
            )
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `pattern condition referring catalog code without explicit declaration passes`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes = listOf(PatternCondition(code = "morning_star")),
                    ),
            )
        // catalog code 자체는 알려져 있으므로 검증 통과
        StrategyDslValidator.validate(dsl) shouldBe ValidationResult.Ok
    }

    @Test
    fun `pattern condition with unknown code returns UNKNOWN_PATTERN_REF`() {
        val dsl =
            buildDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14")),
                entry =
                    LogicalGroup(
                        logic = LogicalOp.AND,
                        nodes = listOf(PatternCondition(code = "not_a_real_pattern")),
                    ),
            )
        val result = StrategyDslValidator.validate(dsl)
        check(result is ValidationResult.Errors)
        result.errors.map { it.code } shouldContain "UNKNOWN_PATTERN_REF"
    }

    // ----------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------

    private fun buildDsl(
        version: String = "1.0",
        indicators: List<IndicatorRef> = emptyList(),
        patterns: List<PatternRef> = emptyList(),
        entry: LogicalGroup =
            LogicalGroup(
                logic = LogicalOp.AND,
                nodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("0")),
                        ),
                    ),
            ),
    ): StrategyDsl =
        StrategyDsl(
            version = version,
            metadata = StrategyMetadata(name = "test"),
            strategy =
                StrategyBody(
                    id = "test_strategy",
                    indicators = indicators,
                    patterns = patterns,
                    entry = entry,
                    exit =
                        LogicalGroup(
                            logic = LogicalOp.AND,
                            nodes =
                                listOf(
                                    Condition(
                                        lhs = PriceOperand(field = PriceField.CLOSE),
                                        operator = ConditionOperator.LESS_THAN,
                                        rhs = ConstantOperand(BigDecimal("0")),
                                    ),
                                ),
                        ),
                ),
        )

    private fun buildNestedGroup(
        depth: Int,
        leafIndicator: String,
    ): LogicalGroup {
        val leaf =
            Condition(
                lhs = IndicatorOperand(alias = leafIndicator),
                operator = ConditionOperator.GREATER_THAN,
                rhs = ConstantOperand(BigDecimal.ONE),
            )
        var current: DslNode = leaf
        repeat(depth - 1) {
            current = LogicalGroup(LogicalOp.AND, listOf(current))
        }
        return current as LogicalGroup
    }
}

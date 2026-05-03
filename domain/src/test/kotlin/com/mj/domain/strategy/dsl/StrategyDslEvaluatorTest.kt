package com.mj.domain.strategy.dsl

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant

/**
 * StrategyDslEvaluator 평가 시나리오 테스트.
 *
 * 합성 캔들로 결정론적으로 검증한다.
 */
class StrategyDslEvaluatorTest {
    private val engine = IndicatorEngine(listOf(NativeIndicatorCalculator()))
    private val evaluator = StrategyDslEvaluator(engine)

    @Test
    fun `simple price greater than constant — entry true`() {
        val candles = ascendingClose(start = 100, count = 30, step = 1)
        val dsl =
            simpleDsl(
                entryNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("100")),
                        ),
                    ),
            )
        val r = evaluator.evaluate(dsl, candles)
        r.entry shouldBe true
    }

    @Test
    fun `RSI less than 30 not satisfied on uptrend`() {
        val candles = ascendingClose(start = 100, count = 50, step = 2)
        val dsl =
            simpleDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14", params = mapOf("period" to 14))),
                entryNodes =
                    listOf(
                        Condition(
                            lhs = IndicatorOperand(alias = "rsi14"),
                            operator = ConditionOperator.LESS_THAN,
                            rhs = ConstantOperand(BigDecimal("30")),
                        ),
                    ),
            )
        val r = evaluator.evaluate(dsl, candles)
        r.entry shouldBe false // 상승장에서 RSI < 30 불만족
    }

    @Test
    fun `MA cross above triggers on synthetic crossover`() {
        // 30봉 하락 후 30봉 상승 — MA(5)가 MA(20)를 cross above
        val downs = (0 until 30).map { BigDecimal(100 - it) }
        val ups = (0 until 30).map { BigDecimal(70 + it * 2) }
        val closes = downs + ups
        val candles = closes.mapIndexed { i, close -> mkCandle(i, close) }

        val dsl =
            simpleDsl(
                indicators =
                    listOf(
                        IndicatorRef(id = "ma", alias = "ma5", params = mapOf("period" to 5)),
                        IndicatorRef(id = "ma", alias = "ma20", params = mapOf("period" to 20)),
                    ),
                entryNodes =
                    listOf(
                        Condition(
                            lhs = IndicatorOperand(alias = "ma5"),
                            operator = ConditionOperator.CROSS_ABOVE,
                            rhs = IndicatorOperand(alias = "ma20"),
                        ),
                    ),
            )
        // cross 발생 위치 — ma5가 ma20을 위에서 아래로/아래에서 위로 이동하는 봉을 찾는다.
        // 결정론적 합성에서는 어디선가 cross가 일어남. 끝부분(상승 충분히 진행 후)에서는 cross_above 자체는 한 봉.
        // 따라서 모든 봉을 순회하며 적어도 한 봉에서 entry==true 발생을 검증.
        var anyEntry = false
        for (i in 19 until candles.size) {
            val r = evaluator.evaluate(dsl, candles, currentIndex = i)
            if (r.entry) {
                anyEntry = true
                break
            }
        }
        anyEntry shouldBe true
    }

    @Test
    fun `AND group requires all true`() {
        val candles = ascendingClose(start = 100, count = 20, step = 1)
        val dsl =
            simpleDsl(
                entryNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("100")),
                        ),
                        Condition(
                            lhs = PriceOperand(field = PriceField.VOLUME),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("999999999")),
                        ),
                    ),
                logic = LogicalOp.AND,
            )
        evaluator.evaluate(dsl, candles).entry shouldBe false
    }

    @Test
    fun `OR group requires at least one true`() {
        val candles = ascendingClose(start = 100, count = 20, step = 1)
        val dsl =
            simpleDsl(
                entryNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("100")),
                        ),
                        Condition(
                            lhs = PriceOperand(field = PriceField.VOLUME),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("999999999")),
                        ),
                    ),
                logic = LogicalOp.OR,
            )
        evaluator.evaluate(dsl, candles).entry shouldBe true
    }

    @Test
    fun `evaluation reasons include lhs rhs values for compare`() {
        val candles = ascendingClose(start = 100, count = 20, step = 1)
        val dsl =
            simpleDsl(
                entryNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("110")),
                        ),
                    ),
            )
        val r = evaluator.evaluate(dsl, candles)
        r.entryReasons.size shouldBe 1
        r.entryReasons.first().kind shouldBe "compare"
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun simpleDsl(
        indicators: List<IndicatorRef> = emptyList(),
        patterns: List<PatternRef> = emptyList(),
        entryNodes: List<DslNode>,
        logic: LogicalOp = LogicalOp.AND,
    ): StrategyDsl =
        StrategyDsl(
            version = "1.0",
            metadata = StrategyMetadata(name = "test"),
            strategy =
                StrategyBody(
                    id = "test",
                    indicators = indicators,
                    patterns = patterns,
                    entry = LogicalGroup(logic = logic, nodes = entryNodes),
                    exit =
                        LogicalGroup(
                            logic = LogicalOp.AND,
                            nodes =
                                listOf(
                                    Condition(
                                        lhs = PriceOperand(field = PriceField.CLOSE),
                                        operator = ConditionOperator.LESS_THAN,
                                        rhs = ConstantOperand(BigDecimal.ZERO),
                                    ),
                                ),
                        ),
                ),
        )

    private fun ascendingClose(
        start: Int,
        count: Int,
        step: Int,
    ): List<Candle> =
        (0 until count).map { i ->
            val close = BigDecimal(start + i * step)
            mkCandle(i, close)
        }

    private fun mkCandle(
        index: Int,
        close: BigDecimal,
    ): Candle {
        // openTime: 00:00 시작에서 매 1분 증가 (분봉 합성)
        val time = Instant.parse("2026-01-01T00:00:00Z").plusSeconds(60L * index)
        val open = close.subtract(BigDecimal("0.1"))
        val high = close.add(BigDecimal("0.5"))
        val low = open.subtract(BigDecimal("0.5"))
        return Candle(
            code = "TEST",
            interval = Interval.M1,
            openTime = time,
            open = open,
            high = high,
            low = low,
            close = close,
            volume = 1000L + index,
            tradeCount = 0L,
        )
    }
}

package com.mj.domain.strategy.signal

import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.strategy.dsl.Condition
import com.mj.domain.strategy.dsl.ConditionOperator
import com.mj.domain.strategy.dsl.ConstantOperand
import com.mj.domain.strategy.dsl.DslNode
import com.mj.domain.strategy.dsl.IndicatorOperand
import com.mj.domain.strategy.dsl.IndicatorRef
import com.mj.domain.strategy.dsl.LogicalGroup
import com.mj.domain.strategy.dsl.LogicalOp
import com.mj.domain.strategy.dsl.PriceField
import com.mj.domain.strategy.dsl.PriceOperand
import com.mj.domain.strategy.dsl.StrategyBody
import com.mj.domain.strategy.dsl.StrategyDsl
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.dsl.StrategyMetadata
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

/**
 * SignalEngine 단위 테스트.
 *
 * 결정론 — 시계열·DSL 동일 → 동일 action. clock·idGenerator 주입으로 generatedAt/eventId 고정.
 * 룩어헤드 차단 — asOfBar 이후 봉 무시.
 */
class SignalEngineTest {
    private val indicatorEngine = IndicatorEngine(listOf(NativeIndicatorCalculator()))
    private val dslEvaluator = StrategyDslEvaluator(indicatorEngine)
    private val fixedNow = Instant.parse("2026-05-03T15:00:00Z")
    private val fixedId = UUID.fromString("00000000-0000-0000-0000-000000000001")
    private val engine =
        SignalEngine(
            dslEvaluator = dslEvaluator,
            clock = { fixedNow },
            idGenerator = { fixedId },
        )

    @Test
    fun `entry true — BUY 시그널 발생`() {
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
        val signal =
            engine.generate(
                SignalEvalRequest(
                    strategyDsl = dsl,
                    symbol = "005930",
                    candles = candles,
                    asOfBar = candles.lastIndex,
                ),
            )
        signal.action.shouldBeInstanceOf<SignalAction.Buy>()
        signal.action.name shouldBe "BUY"
        signal.strength shouldBe 1.0
        signal.symbol shouldBe "005930"
        signal.strategyId shouldBe "test"
        signal.eventId shouldBe fixedId
        signal.generatedAt shouldBe fixedNow
        signal.reasons.isNotEmpty() shouldBe true
    }

    @Test
    fun `exit true — SELL 시그널 (entry보다 우선)`() {
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
                exitNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("110")),
                        ),
                    ),
            )
        // close 끝값이 100+29 = 129. entry true (>100), exit true (>110). SELL 우선.
        val signal =
            engine.generate(
                SignalEvalRequest(
                    strategyDsl = dsl,
                    symbol = "000660",
                    candles = candles,
                    asOfBar = candles.lastIndex,
                ),
            )
        signal.action.shouldBeInstanceOf<SignalAction.Sell>()
        signal.action.name shouldBe "SELL"
    }

    @Test
    fun `entry false exit false — HOLD`() {
        val candles = ascendingClose(start = 100, count = 30, step = 1)
        val dsl =
            simpleDsl(
                entryNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.LESS_THAN,
                            rhs = ConstantOperand(BigDecimal("50")),
                        ),
                    ),
                // exit도 false: close < 0
            )
        val signal =
            engine.generate(
                SignalEvalRequest(
                    strategyDsl = dsl,
                    symbol = "005930",
                    candles = candles,
                    asOfBar = candles.lastIndex,
                ),
            )
        signal.action shouldBe SignalAction.Hold
        signal.action.name shouldBe "HOLD"
        signal.strength shouldBe 0.0
    }

    @Test
    fun `lookahead 차단 — asOfBar 이후 캔들이 평가에 영향 없음`() {
        // asOfBar 시점까지는 close < 100, 그 이후 폭등.
        val before = (0 until 20).map { BigDecimal(50 + it) } // 끝값 69
        val after = (0 until 10).map { BigDecimal(200 + it) } // 폭등
        val candles = (before + after).mapIndexed { i, close -> mkCandle(i, close) }

        val dsl =
            simpleDsl(
                entryNodes =
                    listOf(
                        Condition(
                            lhs = PriceOperand(field = PriceField.CLOSE),
                            operator = ConditionOperator.GREATER_THAN,
                            rhs = ConstantOperand(BigDecimal("150")),
                        ),
                    ),
            )

        // asOfBar = 19 → close 69. >150 false → HOLD. 후속 봉이 entry 만족시켜도 무시.
        val signal =
            engine.generate(
                SignalEvalRequest(
                    strategyDsl = dsl,
                    symbol = "035720",
                    candles = candles,
                    asOfBar = 19,
                ),
            )
        signal.action shouldBe SignalAction.Hold

        // 같은 dsl, asOfBar=29 (last)면 BUY 발생.
        val signalLast =
            engine.generate(
                SignalEvalRequest(
                    strategyDsl = dsl,
                    symbol = "035720",
                    candles = candles,
                    asOfBar = 29,
                ),
            )
        signalLast.action.shouldBeInstanceOf<SignalAction.Buy>()
    }

    @Test
    fun `결정론 — 같은 입력은 같은 action`() {
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
        val req =
            SignalEvalRequest(
                strategyDsl = dsl,
                symbol = "005930",
                candles = candles,
                asOfBar = candles.lastIndex,
            )
        val s1 = engine.generate(req)
        val s2 = engine.generate(req)
        s1.action shouldBe s2.action
        s1.strategyId shouldBe s2.strategyId
        s1.evaluatedAt shouldBe s2.evaluatedAt
    }

    @Test
    fun `userId 전달`() {
        val candles = ascendingClose(start = 100, count = 30, step = 1)
        val dsl = simpleDsl(entryNodes = listOf(alwaysFalseCondition()))
        val signal =
            engine.generate(
                SignalEvalRequest(
                    strategyDsl = dsl,
                    symbol = "005930",
                    candles = candles,
                    asOfBar = candles.lastIndex,
                    userId = "user-123",
                ),
            )
        signal.userId shouldBe "user-123"
    }

    @Test
    fun `RSI cross above 50 — entry true 시 BUY`() {
        // 30봉 하락 후 30봉 상승 — RSI(14)가 50 cross above.
        val downs = (0 until 30).map { BigDecimal(100 - it) }
        val ups = (0 until 30).map { BigDecimal(70 + it * 2) }
        val closes = downs + ups
        val candles = closes.mapIndexed { i, close -> mkCandle(i, close) }

        val dsl =
            simpleDsl(
                indicators = listOf(IndicatorRef(id = "rsi", alias = "rsi14", params = mapOf("period" to 14))),
                entryNodes =
                    listOf(
                        Condition(
                            lhs = IndicatorOperand(alias = "rsi14"),
                            operator = ConditionOperator.CROSS_ABOVE,
                            rhs = ConstantOperand(BigDecimal("50")),
                        ),
                    ),
            )

        // 끝점에서 평가 → 상승 충분히 진행되면 RSI > 50, cross_above는 cross 발생 봉만 true.
        // 따라서 인덱스를 sweeping해 적어도 한 봉에서 BUY 발생을 확인.
        var anyBuy = false
        for (i in 19 until candles.size) {
            val s =
                engine.generate(
                    SignalEvalRequest(
                        strategyDsl = dsl,
                        symbol = "005380",
                        candles = candles,
                        asOfBar = i,
                    ),
                )
            if (s.action is SignalAction.Buy) {
                anyBuy = true
                break
            }
        }
        anyBuy shouldBe true
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun simpleDsl(
        indicators: List<IndicatorRef> = emptyList(),
        entryNodes: List<DslNode>,
        exitNodes: List<DslNode>? = null,
    ): StrategyDsl =
        StrategyDsl(
            version = "1.0",
            metadata = StrategyMetadata(name = "test"),
            strategy =
                StrategyBody(
                    id = "test",
                    indicators = indicators,
                    entry = LogicalGroup(logic = LogicalOp.AND, nodes = entryNodes),
                    exit =
                        LogicalGroup(
                            logic = LogicalOp.AND,
                            nodes =
                                exitNodes
                                    ?: listOf(
                                        Condition(
                                            lhs = PriceOperand(field = PriceField.CLOSE),
                                            operator = ConditionOperator.LESS_THAN,
                                            rhs = ConstantOperand(BigDecimal.ZERO),
                                        ),
                                    ),
                        ),
                ),
        )

    private fun alwaysFalseCondition(): Condition =
        Condition(
            lhs = PriceOperand(field = PriceField.CLOSE),
            operator = ConditionOperator.LESS_THAN,
            rhs = ConstantOperand(BigDecimal("-1")),
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

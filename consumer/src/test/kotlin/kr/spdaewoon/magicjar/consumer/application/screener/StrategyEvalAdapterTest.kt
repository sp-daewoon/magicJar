package kr.spdaewoon.magicjar.consumer.application.screener

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.application.strategy.DslIdentifierMapper
import kr.spdaewoon.magicjar.consumer.application.strategy.EvaluatorAutoDisableCallback
import kr.spdaewoon.magicjar.consumer.application.strategy.JvmStrategyEvaluator
import kr.spdaewoon.magicjar.consumer.application.strategy.StrategyEvalMetricRecorder
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
import kr.spdaewoon.magicjar.domain.indicator.IndicatorException
import kr.spdaewoon.magicjar.domain.indicator.IndicatorPoint
import kr.spdaewoon.magicjar.domain.indicator.IndicatorResult
import kr.spdaewoon.magicjar.domain.indicator.IndicatorSpec
import kr.spdaewoon.magicjar.domain.indicator.port.IndicatorSource
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.pattern.PatternMatch
import kr.spdaewoon.magicjar.domain.pattern.port.PatternRecognizer
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.StrategyDisabledReason
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.ConditionType
import kr.spdaewoon.magicjar.domain.strategy.dsl.IndicatorRef
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.math.BigDecimal
import java.util.concurrent.ThreadPoolExecutor

/**
 * StrategyEvalAdapterImpl 단위 테스트 (ADR 0071 §2-3 C-1 + spec §11-1).
 *
 * 검증 시나리오:
 * 1. 정상 흐름 — JvmStrategyEvaluator forward + EvalOutcome.Success wrap
 * 2. timeout 분기 — StrategyTimeoutException → EvalOutcome.Timeout
 * 3. timeoutMs LOCK — env 정합 (default 500ms)
 *
 * 직접 JvmStrategyEvaluator + strategy-eval-pool reuse 정합 (별도 풀 0).
 */
class StrategyEvalAdapterTest {
    private lateinit var evalPool: ThreadPoolTaskExecutor
    private lateinit var evaluator: JvmStrategyEvaluator

    @BeforeEach
    fun setup() {
        evalPool =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 2
                maxPoolSize = 2
                queueCapacity = 10
                setThreadNamePrefix("strategy-eval-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
                initialize()
            }
        val source = FixedRsiSource()
        val engine = IndicatorEngine(listOf(source))
        val mapper = DslIdentifierMapper()
        val noopPattern =
            object : PatternRecognizer {
                override fun recognize(
                    candles: List<Candle>,
                    patterns: Set<String>,
                ): List<PatternMatch> = emptyList()

                override fun recognize(
                    name: String,
                    candles: List<Candle>,
                ): Boolean = false

                override fun consecutive(
                    direction: String,
                    candles: List<Candle>,
                ): Int = 0
            }
        val metricRecorder = StrategyEvalMetricRecorder()
        val noopAutoDisable =
            object : EvaluatorAutoDisableCallback {
                override fun onAutoDisable(
                    strategyId: Long,
                    reason: StrategyDisabledReason,
                ) {
                    // no-op
                }
            }

        evaluator =
            JvmStrategyEvaluator(
                indicatorEngine = engine,
                patternRecognizer = noopPattern,
                identifierMapper = mapper,
                evalPool = evalPool,
                metricRecorder = metricRecorder,
                autoDisableCallback = noopAutoDisable,
                timeoutMs = 1000L,
                cpuBudgetMs = 50L,
                backtestTimeoutMs = 5000L,
                cpuBudgetViolationThreshold = 5,
            )
    }

    @AfterEach
    fun teardown() {
        evalPool.shutdown()
    }

    @Test
    fun `정상 흐름 — JvmStrategyEvaluator forward 후 EvalOutcome_Success wrap`() {
        val adapter = StrategyEvalAdapterImpl(evaluator, timeoutMs = 500L)

        // rsi(14) < 30 → fixedRsiSource는 25 반환 → BUY 매칭
        val ast =
            Condition(
                conditionType = ConditionType.THRESHOLD,
                left = IndicatorRef(name = "rsi", params = listOf(BigDecimal("14"))),
                operator = Operator.LT,
                right = LiteralValue(value = BigDecimal("30")),
            )
        val candles = buildCandles(30)

        val outcome = adapter.evaluate(strategyId = 1L, ast = ast, candles = candles)

        assertTrue(outcome is EvalOutcome.Success, "outcome=$outcome")
        val success = outcome as EvalOutcome.Success
        assertEquals(Signal.BUY, success.result.signal)
    }

    @Test
    fun `timeout LOCK — universe screening 한정 default 500ms`() {
        val adapter = StrategyEvalAdapterImpl(evaluator, timeoutMs = 500L)
        assertEquals(500L, adapter.timeoutMs())
    }

    @Test
    fun `HOLD 시그널 — candle 부족 시 graceful HOLD (sandbox 정책 reuse)`() {
        val adapter = StrategyEvalAdapterImpl(evaluator, timeoutMs = 500L)

        val ast =
            Condition(
                conditionType = ConditionType.THRESHOLD,
                left = IndicatorRef(name = "rsi", params = listOf(BigDecimal("14"))),
                operator = Operator.LT,
                right = LiteralValue(value = BigDecimal("30")),
            )
        // candle 0건 → JvmStrategyEvaluator가 HOLD + INSUFFICIENT_DATA reason
        val candles = emptyList<Candle>()

        val outcome = adapter.evaluate(strategyId = 1L, ast = ast, candles = candles)

        assertTrue(outcome is EvalOutcome.Success, "outcome=$outcome — graceful HOLD")
        val success = outcome as EvalOutcome.Success
        assertEquals(Signal.HOLD, success.result.signal)
    }

    private fun buildCandles(count: Int): List<Candle> {
        val now = Clock.System.now()
        return (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = now.minus(kotlin.time.Duration.parse("${count - i}d")),
                openRaw = BigDecimal("70000"),
                highRaw = BigDecimal("71000"),
                lowRaw = BigDecimal("69500"),
                closeRaw = BigDecimal("70500"),
                openAdj = BigDecimal("70000"),
                highAdj = BigDecimal("71000"),
                lowAdj = BigDecimal("69500"),
                closeAdj = BigDecimal("70500"),
                volume = 1_000_000L,
                source = "TEST",
            )
        }
    }

    /**
     * RSI(14) 항상 25 반환 — BUY 매칭 fixture (< 30 임계).
     */
    private class FixedRsiSource : IndicatorSource {
        override val name: String = "fixed-rsi"
        override val priority: Int = 100

        override fun supports(spec: IndicatorSpec): Boolean = spec.name == "rsi"

        override fun calculate(
            spec: IndicatorSpec,
            candles: List<Candle>,
        ): IndicatorResult {
            if (candles.isEmpty()) {
                throw IndicatorException.InsufficientCandlesException(required = 14, actual = 0)
            }
            val last: Instant = candles.last().ts
            return IndicatorResult(
                spec = spec,
                series = listOf(IndicatorPoint(time = last, values = mapOf("value" to BigDecimal("25")))),
                source = "fixed-rsi",
            )
        }
    }
}

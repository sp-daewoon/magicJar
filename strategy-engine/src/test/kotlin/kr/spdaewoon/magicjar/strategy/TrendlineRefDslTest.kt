package kr.spdaewoon.magicjar.strategy

import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.indicator.IndicatorEngine
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
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineAnchors
import kr.spdaewoon.magicjar.domain.strategy.dsl.TrendlineRef
import kr.spdaewoon.magicjar.domain.strategy.port.TrendlinePricePort
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.math.BigDecimal
import java.util.concurrent.ThreadPoolExecutor

/**
 * TrendlineRef DSL — parser 왕복 + evaluator 선형보간 분기 (ADR 0140 §5, spec R1 테스트).
 *
 * 커버리지: parser `trendline(123)` → TrendlineRef · evaluator 정상 보간 BUY · 범위밖 false ·
 * 선부재 false · port 미주입 false · 기존 DSL 회귀 0.
 */
class TrendlineRefDslTest {
    private val parser = KisYamlDslParser(astDepthLimit = 32, nodeCountLimit = 10000, indicatorCallLimit = 50)

    private lateinit var evalPool: ThreadPoolTaskExecutor
    private lateinit var metricRecorder: StrategyEvalMetricRecorder
    private lateinit var disabledLog: MutableList<Pair<Long, StrategyDisabledReason>>

    @BeforeEach
    fun setup() {
        evalPool =
            ThreadPoolTaskExecutor().apply {
                corePoolSize = 2
                maxPoolSize = 2
                queueCapacity = 10
                setThreadNamePrefix("trendline-eval-test-")
                setRejectedExecutionHandler(ThreadPoolExecutor.CallerRunsPolicy())
                initialize()
            }
        metricRecorder = StrategyEvalMetricRecorder()
        disabledLog = mutableListOf()
    }

    private fun evaluatorWith(port: TrendlinePricePort?): JvmStrategyEvaluator {
        val engine = IndicatorEngine(listOf(FakeCloseSource()))
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
        return JvmStrategyEvaluator(
            indicatorEngine = engine,
            patternRecognizer = noopPattern,
            identifierMapper = DslIdentifierMapper(),
            evalPool = evalPool,
            metricRecorder = metricRecorder,
            autoDisableCallback = { id, r -> disabledLog.add(id to r) },
            timeoutMs = 5000,
            cpuBudgetMs = 50,
            backtestTimeoutMs = 5000,
            cpuBudgetViolationThreshold = 5,
            trendlinePricePort = port,
        )
    }

    // ── parser 왕복 ────────────────────────────────────────────────────

    @Test
    fun `parser — trendline(123) → TrendlineRef`() {
        val ast = parser.parse("close crosses_above trendline(123)")
        assertInstanceOf(Condition::class.java, ast)
        val cond = ast as Condition
        assertEquals(ConditionType.CROSSOVER, cond.conditionType)
        assertEquals(Operator.CROSSES_ABOVE, cond.operator)
        assertInstanceOf(IndicatorRef::class.java, cond.left)
        assertEquals("close", (cond.left as IndicatorRef).name)
        assertInstanceOf(TrendlineRef::class.java, cond.right)
        assertEquals(123L, (cond.right as TrendlineRef).trendlineId)
    }

    @Test
    fun `parser — trendline은 화이트리스트 오염 없이 검증 통과`() {
        // trendline은 IndicatorRef가 아니라 TrendlineRef → unknown symbol 예외 0 + indicator 예산 0.
        // 기준선 `close > 0`(close만 indicator 1) 대비 trendline이 예산을 추가하지 않음을 검증.
        val baseline = parser.validateStatic(parser.parse("close > 0"))
        val withTrendline = parser.validateStatic(parser.parse("close > trendline(999)"))
        assertEquals(
            baseline.indicatorCallCount,
            withTrendline.indicatorCallCount,
            "trendline은 indicator 예산을 추가하지 않아야 함(close만 카운트)",
        )
    }

    @Test
    fun `parser — trendline 인자 없거나 다중이면 syntax error`() {
        val ex1 = runCatching { parser.parse("close > trendline()") }.exceptionOrNull()
        assertInstanceOf(kr.spdaewoon.magicjar.domain.strategy.StrategyException.DslSyntaxException::class.java, ex1)
        val ex2 = runCatching { parser.parse("close > trendline(1, 2)") }.exceptionOrNull()
        assertInstanceOf(kr.spdaewoon.magicjar.domain.strategy.StrategyException.DslSyntaxException::class.java, ex2)
    }

    // ── evaluator 분기 ─────────────────────────────────────────────────

    @Test
    fun `evaluator 정상 — close가 선 위면 THRESHOLD BUY`() {
        // 평평한 선 가격 100 (전 구간 커버). close = 100 + i → 마지막 119 > 100 → BUY
        val port = fixedPort(123L, flatLine(price = "100", coverAll = true))
        val ast = parser.parse("close > trendline(123)")
        val result = evaluatorWith(port).evaluate(1L, ast, candleSeries(20))
        assertEquals(Signal.BUY, result.signal)
        assertEquals("THRESHOLD", result.reason["trigger"])
    }

    @Test
    fun `evaluator 범위 밖 — 선 밖이면 조건 false HOLD`() {
        // anchor2Ts가 캔들 마지막 ts보다 과거 + extendRight=false → priceAt null → false
        val outOfRange =
            TrendlineAnchors(
                anchor1Ts = Instant.fromEpochSeconds(1_600_000_000L),
                anchor1Price = BigDecimal("100"),
                anchor2Ts = Instant.fromEpochSeconds(1_600_100_000L),
                anchor2Price = BigDecimal("100"),
                extendLeft = false,
                extendRight = false,
            )
        val ast = parser.parse("close > trendline(123)")
        val result = evaluatorWith(fixedPort(123L, outOfRange)).evaluate(2L, ast, candleSeries(20))
        assertEquals(Signal.HOLD, result.signal)
        assertEquals("TRENDLINE_OUT_OF_RANGE", triggerOf(result.reason))
    }

    @Test
    fun `evaluator 선 부재 — port가 null 반환하면 조건 false HOLD`() {
        val emptyPort =
            object : TrendlinePricePort {
                override fun anchorsOf(trendlineId: Long): TrendlineAnchors? = null
            }
        val ast = parser.parse("close > trendline(123)")
        val result = evaluatorWith(emptyPort).evaluate(3L, ast, candleSeries(20))
        assertEquals(Signal.HOLD, result.signal)
        assertEquals("TRENDLINE_NOT_FOUND", triggerOf(result.reason))
    }

    @Test
    fun `evaluator port 미주입 — bean 부재 시 조건 false HOLD (silent fallback 금지)`() {
        val ast = parser.parse("close > trendline(123)")
        val result = evaluatorWith(port = null).evaluate(4L, ast, candleSeries(20))
        assertEquals(Signal.HOLD, result.signal)
        assertEquals("TRENDLINE_PORT_MISSING", triggerOf(result.reason))
    }

    // ── 회귀 ───────────────────────────────────────────────────────────

    @Test
    fun `회귀 — 기존 close 비교 DSL은 port 주입돼도 동일 동작`() {
        val ast = parser.parse("close > 0")
        val result = evaluatorWith(fixedPort(1L, flatLine("100", coverAll = true))).evaluate(5L, ast, candleSeries(20))
        assertEquals(Signal.BUY, result.signal)
    }

    // ── fixtures ───────────────────────────────────────────────────────

    @Suppress("UNCHECKED_CAST")
    private fun triggerOf(reason: Map<String, Any>): Any? {
        // THRESHOLD 조건이 false로 접힐 때 reason은 nullOperand의 reason(= trigger 키 포함)
        return reason["trigger"]
    }

    private fun fixedPort(
        id: Long,
        anchors: TrendlineAnchors,
    ): TrendlinePricePort =
        object : TrendlinePricePort {
            override fun anchorsOf(trendlineId: Long): TrendlineAnchors? = if (trendlineId == id) anchors else null
        }

    /** 캔들 시계열 전 구간을 덮는 평평한 선. */
    private fun flatLine(
        price: String,
        coverAll: Boolean,
    ): TrendlineAnchors =
        TrendlineAnchors(
            anchor1Ts = Instant.fromEpochSeconds(1_699_000_000L),
            anchor1Price = BigDecimal(price),
            anchor2Ts = Instant.fromEpochSeconds(1_703_000_000L),
            anchor2Price = BigDecimal(price),
            extendLeft = coverAll,
            extendRight = coverAll,
        )

    private fun candleSeries(count: Int): List<Candle> =
        (0 until count).map { i ->
            Candle(
                code = "005930",
                interval = CandleInterval.ONE_DAY,
                ts = Instant.fromEpochSeconds(1_700_000_000L + i * 86_400L),
                openRaw = BigDecimal("100"),
                highRaw = BigDecimal("105"),
                lowRaw = BigDecimal("95"),
                closeRaw = BigDecimal("100").add(BigDecimal(i)),
                openAdj = null,
                highAdj = null,
                lowAdj = null,
                closeAdj = null,
                volume = 1_000_000L,
                source = "test",
            )
        }

    private class FakeCloseSource : IndicatorSource {
        override val name: String = "fake-close"
        override val priority: Int = 1

        override fun supports(spec: IndicatorSpec): Boolean = true

        override fun calculate(
            spec: IndicatorSpec,
            candles: List<Candle>,
        ): IndicatorResult {
            val series = candles.map { IndicatorPoint(time = it.ts, values = mapOf(spec.name to BigDecimal("50"))) }
            return IndicatorResult(spec = spec, series = series, source = name)
        }
    }
}

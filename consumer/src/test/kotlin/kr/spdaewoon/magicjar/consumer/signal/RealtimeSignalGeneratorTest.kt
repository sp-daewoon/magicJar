package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.Condition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslNode
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.dsl.Operator
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.strategy.JvmStrategyEvaluator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import kr.spdaewoon.magicjar.domain.signal.Signal as GeneratedSignal
import kr.spdaewoon.magicjar.domain.strategy.Signal as StrategySignal

class RealtimeSignalGeneratorTest {
    private val strategyLookup = mockk<StrategyLookupPort>()
    private val signalRepository = mockk<SignalRepository>()
    private val candleRepository = mockk<CandleRepository>()
    private val evaluator = mockk<JvmStrategyEvaluator>()
    private val dedupeGuard = mockk<SignalDedupeGuard>()
    private val signalKafkaProducer = mockk<SignalKafkaProducer>()
    private val fixedNow = Instant.parse("2026-05-20T06:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    @Test
    fun `평가 시 DB 과거 캔들에 boundary candle을 붙여 lookback 순서로 넘긴다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:15:00Z")
        val boundary = candle(boundaryTs, "150")
        val capturedCandles = slot<List<Candle>>()

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(
                candle("2026-05-20T00:00:00Z", "100"),
                candle("2026-05-20T00:05:00Z", "101"),
                candle("2026-05-20T00:10:00Z", "102"),
                candle(boundaryTs, "999"),
                candle("2026-05-20T00:20:00Z", "200"),
            )
        every { evaluator.evaluate(42L, buyAst, capture(capturedCandles)) } returns holdResult()

        generator(evalLookbackCandles = 3).onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = boundary,
        )

        assertThat(capturedCandles.captured.map { it.ts }).containsExactly(
            Instant.parse("2026-05-20T00:05:00Z"),
            Instant.parse("2026-05-20T00:10:00Z"),
            boundaryTs,
        )
        assertThat(capturedCandles.captured.map { it.closeRaw.toPlainString() }).containsExactly("101", "102", "150")
        verify(exactly = 0) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `buyCondition HOLD 이후 sellCondition 충족 시 SELL 시그널을 저장하고 발행한다`() {
        val buyAst = ast("1", Operator.LT, "0")
        val sellAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:15:00Z")
        val savedSignal = slot<GeneratedSignal>()
        val publishedSignal = slot<GeneratedSignal>()

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst, sellAst = sellAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(candle("2026-05-20T00:10:00Z", "100"))
        every { evaluator.evaluate(42L, buyAst, any()) } returns holdResult()
        every { evaluator.evaluate(42L, sellAst, any()) } returns
            StrategyEvalResult(
                signal = StrategySignal.BUY,
                strength = BigDecimal("0.70"),
                reason = mapOf("trigger" to "SELL_CONDITION"),
                evaluatedAt = fixedNow,
                evalTimeMs = 1L,
            )
        every { signalRepository.save(capture(savedSignal)) } answers { savedSignal.captured.copy(id = 77L) }
        every { signalKafkaProducer.publish(capture(publishedSignal)) } just Runs

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "120"),
        )

        assertThat(savedSignal.captured.side).isEqualTo(SignalSide.SELL)
        assertThat(savedSignal.captured.strength).isEqualTo(70)
        assertThat(savedSignal.captured.reason).isEqualTo("SELL_CONDITION")
        assertThat(savedSignal.captured.priceAtSignal.toPlainString()).isEqualTo("120")
        assertThat(publishedSignal.captured.id).isEqualTo(77L)
        assertThat(publishedSignal.captured.side).isEqualTo(SignalSide.SELL)
    }

    private fun generator(evalLookbackCandles: Int = 500): RealtimeSignalGenerator =
        RealtimeSignalGenerator(
            strategyLookupProvider = providerOf(strategyLookup),
            signalRepositoryProvider = providerOf(signalRepository),
            candleRepositoryProvider = providerOf(candleRepository),
            jvmStrategyEvaluator = evaluator,
            signalDedupeGuard = dedupeGuard,
            signalKafkaProducer = signalKafkaProducer,
            evalLookbackCandles = evalLookbackCandles,
            clock = clock,
        )

    private fun strategy(
        buyAst: DslNode,
        sellAst: DslNode? = null,
    ): Strategy =
        Strategy(
            id = 42L,
            userId = 1234L,
            definition =
                StrategyDefinition(
                    dsl =
                        DslStrategyDefinition(
                            name = "test",
                            buyCondition = buyAst,
                            sellCondition = sellAst,
                        ),
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 3,
                    indicatorCallCount = 0,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
        )

    private fun ast(
        left: String,
        operator: Operator,
        right: String,
    ): DslNode =
        Condition(
            left = LiteralValue(BigDecimal(left)),
            operator = operator,
            right = LiteralValue(BigDecimal(right)),
        )

    private fun holdResult(): StrategyEvalResult =
        StrategyEvalResult(
            signal = StrategySignal.HOLD,
            strength = BigDecimal.ZERO,
            reason = mapOf("trigger" to "HOLD"),
            evaluatedAt = fixedNow,
            evalTimeMs = 1L,
        )

    private fun candle(
        ts: String,
        close: String,
    ): Candle = candle(Instant.parse(ts), close)

    private fun candle(
        ts: Instant,
        close: String,
    ): Candle {
        val price = BigDecimal(close)
        return Candle(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = ts,
            openRaw = price,
            highRaw = price,
            lowRaw = price,
            closeRaw = price,
            openAdj = null,
            highAdj = null,
            lowAdj = null,
            closeAdj = null,
            volume = 1_000L,
            source = "TEST",
        )
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }
}

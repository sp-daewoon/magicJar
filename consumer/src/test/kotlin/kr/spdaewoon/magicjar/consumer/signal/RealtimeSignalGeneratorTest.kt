package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.infrastructure.session.SimpleMarketSessionResolver
import kr.spdaewoon.magicjar.consumer.reconciliation.DataReadinessResult
import kr.spdaewoon.magicjar.consumer.reconciliation.DataReadinessService
import kr.spdaewoon.magicjar.consumer.reconciliation.DataReadinessStatus
import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.CandleRepository
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.signal.port.SignalRepository
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.StrategyStatus
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
    private val readinessService = mockk<DataReadinessService>()
    private val riskPositionPort = mockk<RiskPositionPort>()
    private val evaluator = mockk<JvmStrategyEvaluator>()
    private val dedupeGuard = mockk<SignalDedupeGuard>()
    private val signalKafkaProducer = mockk<SignalKafkaProducer>()
    private val marketSessionResolver = SimpleMarketSessionResolver()
    private val businessDayCalendar =
        mockk<BusinessDayCalendar>().also {
            every { it.isBusinessDay(any()) } returns true
        }
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
    fun `buyCondition 충족 시 BUY 시그널을 직접 저장하고 Kafka로 발행한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val sellAst = ast("0", Operator.GT, "1")
        val boundaryTs = Instant.parse("2026-05-20T00:30:00Z")
        val savedSignal = slot<GeneratedSignal>()
        val publishedSignal = slot<GeneratedSignal>()

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst, sellAst = sellAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(candle("2026-05-20T00:25:00Z", "100"))
        every { evaluator.evaluate(42L, buyAst, any()) } returns
            StrategyEvalResult(
                signal = StrategySignal.BUY,
                strength = BigDecimal("0.85"),
                reason = mapOf("trigger" to "MA_CROSS", "lhs" to "MA(5)=120", "rhs" to "MA(20)=110"),
                evaluatedAt = fixedNow,
                evalTimeMs = 2L,
            )
        every { signalRepository.save(capture(savedSignal)) } answers { savedSignal.captured.copy(id = 88L) }
        every { signalKafkaProducer.publish(capture(publishedSignal)) } just Runs

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        // BUY direct path — sellCondition 평가는 호출 0 (BUY 우선 LOCK 정합)
        verify(exactly = 1) { evaluator.evaluate(42L, buyAst, any()) }
        verify(exactly = 0) { evaluator.evaluate(42L, sellAst, any()) }
        // signal save 호출 + id 채번 확인 + Kafka publish 호출 + id 포함
        assertThat(savedSignal.captured.side).isEqualTo(SignalSide.BUY)
        assertThat(savedSignal.captured.strength).isEqualTo(85)
        assertThat(savedSignal.captured.reason).isEqualTo("MA_CROSS: MA(5)=120 vs MA(20)=110")
        assertThat(savedSignal.captured.priceAtSignal.toPlainString()).isEqualTo("150")
        assertThat(savedSignal.captured.code).isEqualTo("005930")
        assertThat(savedSignal.captured.interval).isEqualTo(CandleInterval.FIVE_MIN)
        assertThat(savedSignal.captured.ts).isEqualTo(boundaryTs)
        // Kafka publish는 저장 후 id가 채번된 signal로 호출되어야 한다
        assertThat(publishedSignal.captured.id).isEqualTo(88L)
        assertThat(publishedSignal.captured.side).isEqualTo(SignalSide.BUY)
        verify(exactly = 1) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `BUY_BLOCKED 전략은 신규 BUY 시그널을 저장하거나 발행하지 않는다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:35:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst, status = StrategyStatus.BUY_BLOCKED))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(candle("2026-05-20T00:30:00Z", "100"))
        every { evaluator.evaluate(42L, buyAst, any()) } returns
            StrategyEvalResult(
                signal = StrategySignal.BUY,
                strength = BigDecimal("0.80"),
                reason = mapOf("trigger" to "BUY_CONDITION"),
                evaluatedAt = fixedNow,
                evalTimeMs = 1L,
            )

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        verify(exactly = 0) { signalRepository.save(any()) }
        verify(exactly = 0) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `BUY_BLOCKED는 해당 strategy_id의 신규 BUY만 막고 다른 ACTIVE 전략은 계속 BUY를 발행한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:36:00Z")
        val savedSignal = slot<GeneratedSignal>()
        val publishedSignal = slot<GeneratedSignal>()

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(
                strategy(id = 42L, buyAst = buyAst, status = StrategyStatus.BUY_BLOCKED),
                strategy(id = 77L, buyAst = buyAst, status = StrategyStatus.ACTIVE),
            )
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { dedupeGuard.tryAcquire(77L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(candle("2026-05-20T00:30:00Z", "100"))
        every { evaluator.evaluate(any(), buyAst, any()) } returns
            StrategyEvalResult(
                signal = StrategySignal.BUY,
                strength = BigDecimal("0.80"),
                reason = mapOf("trigger" to "BUY_CONDITION"),
                evaluatedAt = fixedNow,
                evalTimeMs = 1L,
            )
        every { signalRepository.save(capture(savedSignal)) } answers { savedSignal.captured.copy(id = 101L) }
        every { signalKafkaProducer.publish(capture(publishedSignal)) } just Runs

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        assertThat(savedSignal.captured.strategyId).isEqualTo(77L)
        assertThat(savedSignal.captured.side).isEqualTo(SignalSide.BUY)
        assertThat(publishedSignal.captured.strategyId).isEqualTo(77L)
        verify(exactly = 1) { signalRepository.save(any()) }
        verify(exactly = 1) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `dedupeGuard tryAcquire false 시 evaluator 호출과 signal save를 모두 skip 한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val sellAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:45:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst, sellAst = sellAst))
        // dedupe acquire 실패 — 이전 동일 boundary 처리 흔적 (Redis SETNX 30s window LOCK)
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns false

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "160"),
        )

        // evaluator·signal save·Kafka publish 모두 0 — dedupe LOCK 정합
        verify(exactly = 0) { evaluator.evaluate(any(), any(), any()) }
        verify(exactly = 0) { signalRepository.save(any()) }
        verify(exactly = 0) { signalKafkaProducer.publish(any()) }
        verify(exactly = 0) { candleRepository.findByCodeAndInterval(any(), any()) }
        // dedupe guard 자체는 호출되어야 함 (정상 path 진입 evidence)
        verify(exactly = 1) { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) }
    }

    @Test
    fun `data readiness READY가 아니면 dedupe와 strategy 평가 전에 signal 생성을 fail-closed 한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:50:00Z")
        val readiness =
            DataReadinessResult(
                status = DataReadinessStatus.RECONCILING,
                reason = "symbol-detail-enter",
                code = "005930",
                interval = CandleInterval.FIVE_MIN,
                boundaryTs = boundaryTs,
                latestIntervalTs = null,
                latestOneMinTs = null,
            )

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst))
        every { readinessService.check("005930", CandleInterval.FIVE_MIN, boundaryTs) } returns readiness
        every { readinessService.auditSkip(readiness, listOf(42L)) } just Runs

        generator(dataReadinessService = readinessService).onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "160"),
        )

        verify(exactly = 1) { readinessService.check("005930", CandleInterval.FIVE_MIN, boundaryTs) }
        verify(exactly = 1) { readinessService.auditSkip(readiness, listOf(42L)) }
        verify(exactly = 0) { dedupeGuard.tryAcquire(any(), any(), any(), any()) }
        verify(exactly = 0) { evaluator.evaluate(any(), any(), any()) }
        verify(exactly = 0) { signalRepository.save(any()) }
        verify(exactly = 0) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `signalRepository save 예외 발생 시 Kafka publish 0 + listener thread 살아 있다`() {
        val buyAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T01:00:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(candle("2026-05-20T00:55:00Z", "100"))
        every { evaluator.evaluate(42L, buyAst, any()) } returns
            StrategyEvalResult(
                signal = StrategySignal.BUY,
                strength = BigDecimal("0.50"),
                reason = mapOf("trigger" to "RULE"),
                evaluatedAt = fixedNow,
                evalTimeMs = 1L,
            )
        // signal save 실패 — UQ violation 또는 영속 오류
        every { signalRepository.save(any()) } throws RuntimeException("UQ violation (strategyId, code, interval, ts)")

        // listener thread를 죽이지 않고 graceful catch 되어야 함 (assert no throw)
        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "170"),
        )

        // save는 1회 시도, publish는 0회 (save 실패 시 publish skip 정합)
        verify(exactly = 1) { signalRepository.save(any()) }
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

    @Test
    fun `BUY_BLOCKED 전략도 sellCondition 충족 시 SELL 시그널은 저장하고 발행한다`() {
        val buyAst = ast("1", Operator.LT, "0")
        val sellAst = ast("1", Operator.GT, "0")
        val boundaryTs = Instant.parse("2026-05-20T00:16:00Z")
        val savedSignal = slot<GeneratedSignal>()
        val publishedSignal = slot<GeneratedSignal>()

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst, sellAst = sellAst, status = StrategyStatus.BUY_BLOCKED))
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
        every { signalRepository.save(capture(savedSignal)) } answers { savedSignal.captured.copy(id = 78L) }
        every { signalKafkaProducer.publish(capture(publishedSignal)) } just Runs

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "120"),
        )

        assertThat(savedSignal.captured.side).isEqualTo(SignalSide.SELL)
        assertThat(publishedSignal.captured.id).isEqualTo(78L)
        assertThat(publishedSignal.captured.side).isEqualTo(SignalSide.SELL)
    }

    @Test
    fun `takeProfitPct 도달 시 보유 포지션 기준 SELL 시그널을 저장하고 발행한다`() {
        val buyAst = ast("0", Operator.GT, "1")
        val boundaryTs = Instant.parse("2026-05-20T00:20:00Z")
        val savedSignal = slot<GeneratedSignal>()

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(strategy(buyAst = buyAst, takeProfitPct = BigDecimal("5.00")))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.FIVE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.FIVE_MIN) } returns
            listOf(candle("2026-05-20T00:15:00Z", "100"))
        every { evaluator.evaluate(42L, buyAst, any()) } returns holdResult()
        every { riskPositionPort.findCurrentByUser("1234") } returns
            listOf(
                Position(
                    userId = "1234",
                    code = "005930",
                    quantity = 3,
                    avgPrice = BigDecimal("100"),
                    updatedAt = fixedNow,
                ),
            )
        every { signalRepository.save(capture(savedSignal)) } answers { savedSignal.captured.copy(id = 99L) }
        every { signalKafkaProducer.publish(any()) } just Runs

        generator(riskPositionPort = riskPositionPort).onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "106"),
        )

        assertThat(savedSignal.captured.side).isEqualTo(SignalSide.SELL)
        assertThat(savedSignal.captured.strength).isEqualTo(100)
        assertThat(savedSignal.captured.reason).isEqualTo("TAKE_PROFIT")
        verify(exactly = 1) { riskPositionPort.findCurrentByUser("1234") }
        verify(exactly = 1) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `장전 08시30분 boundary는 정규장 밖이라 strategy 평가를 skip 한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        // KST 2026-05-20(수) 08:30 = UTC 2026-05-19T23:30:00Z (NXT pre-hour 캔들 마감 — 정책상 의도된 적재)
        val boundaryTs = Instant.parse("2026-05-19T23:30:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns
            listOf(strategy(buyAst = buyAst))

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.ONE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        // 게이트 차단 — dedupe·evaluate·save·publish 모두 0
        verify(exactly = 0) { dedupeGuard.tryAcquire(any(), any(), any(), any()) }
        verify(exactly = 0) { evaluator.evaluate(any(), any(), any()) }
        verify(exactly = 0) { signalRepository.save(any()) }
        verify(exactly = 0) { signalKafkaProducer.publish(any()) }
    }

    @Test
    fun `정규장 10시 boundary는 평가를 진행한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        // KST 2026-05-20(수) 10:00 = UTC 2026-05-20T01:00:00Z (정규장)
        val boundaryTs = Instant.parse("2026-05-20T01:00:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns
            listOf(strategy(buyAst = buyAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.ONE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns emptyList()
        every { evaluator.evaluate(42L, buyAst, any()) } returns holdResult()

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.ONE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        // 게이트 통과 — 정상 평가 진입
        verify(exactly = 1) { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.ONE_MIN, boundaryTs) }
        verify(exactly = 1) { evaluator.evaluate(42L, buyAst, any()) }
    }

    @Test
    fun `09시 정각 boundary는 정규장 진입으로 평가를 진행한다 (off-by-one 경계)`() {
        val buyAst = ast("1", Operator.GT, "0")
        // KST 2026-05-20(수) 09:00 정각 = UTC 2026-05-20T00:00:00Z (=08:59 1m 캔들 마감 boundary)
        val boundaryTs = Instant.parse("2026-05-20T00:00:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns
            listOf(strategy(buyAst = buyAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.ONE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns emptyList()
        every { evaluator.evaluate(42L, buyAst, any()) } returns holdResult()

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.ONE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        verify(exactly = 1) { evaluator.evaluate(42L, buyAst, any()) }
    }

    @Test
    fun `15시 30분 boundary는 정규장 종료 경계라 평가를 skip 한다 (off-by-one 경계)`() {
        val buyAst = ast("1", Operator.GT, "0")
        // KST 2026-05-20(수) 15:30 = UTC 2026-05-20T06:30:00Z (resolver 정규장 09:00~15:29 — 15:30은 Closed)
        val boundaryTs = Instant.parse("2026-05-20T06:30:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns
            listOf(strategy(buyAst = buyAst))

        generator().onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.ONE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        verify(exactly = 0) { evaluator.evaluate(any(), any(), any()) }
    }

    @Test
    fun `게이트 토글 off 시 장전 boundary도 평가를 진행한다`() {
        val buyAst = ast("1", Operator.GT, "0")
        // KST 08:30 장전 boundary — 토글 off면 게이트 우회
        val boundaryTs = Instant.parse("2026-05-19T23:30:00Z")

        every { strategyLookup.findActiveByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns
            listOf(strategy(buyAst = buyAst))
        every { dedupeGuard.tryAcquire(42L, "005930", CandleInterval.ONE_MIN, boundaryTs) } returns true
        every { candleRepository.findByCodeAndInterval("005930", CandleInterval.ONE_MIN) } returns emptyList()
        every { evaluator.evaluate(42L, buyAst, any()) } returns holdResult()

        generator(marketHoursGateEnabled = false).onBoundaryClosed(
            code = "005930",
            interval = CandleInterval.ONE_MIN,
            boundaryTs = boundaryTs,
            candle = candle(boundaryTs, "150"),
        )

        // 게이트 비활성 — 평가 진입
        verify(exactly = 1) { evaluator.evaluate(42L, buyAst, any()) }
    }

    private fun generator(
        evalLookbackCandles: Int = 500,
        dataReadinessService: DataReadinessService? = null,
        riskPositionPort: RiskPositionPort? = null,
        marketHoursGateEnabled: Boolean = true,
    ): RealtimeSignalGenerator =
        RealtimeSignalGenerator(
            strategyLookupProvider = providerOf(strategyLookup),
            signalRepositoryProvider = providerOf(signalRepository),
            candleRepositoryProvider = providerOf(candleRepository),
            dataReadinessServiceProvider =
                dataReadinessService
                    ?.let { providerOf(it) }
                    ?: emptyProvider(),
            riskPositionPortProvider =
                riskPositionPort
                    ?.let { providerOf(it) }
                    ?: emptyProvider(),
            strategyPositionPortProvider = emptyProvider(),
            jvmStrategyEvaluator = evaluator,
            signalDedupeGuard = dedupeGuard,
            signalKafkaProducer = signalKafkaProducer,
            marketSessionResolver = marketSessionResolver,
            businessDayCalendarProvider = providerOf(businessDayCalendar),
            evalLookbackCandles = evalLookbackCandles,
            marketHoursGateEnabled = marketHoursGateEnabled,
            clock = clock,
        )

    private fun strategy(
        id: Long = 42L,
        buyAst: DslNode,
        sellAst: DslNode? = null,
        takeProfitPct: BigDecimal? = null,
        status: StrategyStatus = StrategyStatus.ACTIVE,
    ): Strategy =
        Strategy(
            id = id,
            userId = 1234L,
            status = status,
            definition =
                StrategyDefinition(
                    dsl =
                        DslStrategyDefinition(
                            name = "test",
                            buyCondition = buyAst,
                            sellCondition = sellAst,
                        ),
                    takeProfitPct = takeProfitPct,
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

    private fun <T : Any> emptyProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns null
        }
}

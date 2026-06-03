package kr.spdaewoon.magicjar.api.scenario

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.api.persistence.CandleJpaEntity
import kr.spdaewoon.magicjar.api.persistence.CandleJpaRepository
import kr.spdaewoon.magicjar.api.persistence.scenario.ScenarioRunJpaEntity
import kr.spdaewoon.magicjar.api.service.backtest.BacktestEvaluatorBridge
import kr.spdaewoon.magicjar.domain.scenario.ScenarioReplayResult
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Signal
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyEvalResult
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.LiteralValue
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyRepositoryPort
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.springframework.data.domain.PageImpl
import java.math.BigDecimal
import java.time.Instant

class ScenarioReplayServiceTest {
    private val strategyRepository: StrategyRepositoryPort = mock()
    private val candleRepository: CandleJpaRepository = mock()
    private val evaluatorBridge: BacktestEvaluatorBridge = mock()
    private val persister: ScenarioReplayPersister = mock()
    private val queryService: ScenarioReplayQueryService = mock()
    private val service =
        ScenarioReplayService(
            strategyRepository = strategyRepository,
            candleRepository = candleRepository,
            evaluatorBridge = evaluatorBridge,
            persister = persister,
            queryService = queryService,
            objectMapper = ObjectMapper(),
            maxCandlesPerRun = 100,
            maxEventsPerRun = 500,
        )

    @Test
    fun `holding position maps true sell condition to replay SELL`() {
        var captured: ScenarioReplayResult? = null
        whenever(strategyRepository.findById(8L)).thenReturn(sampleStrategy())
        whenever(candleRepository.findByCodeAndIntervalAndTsBetween(any(), any(), any(), any()))
            .thenReturn(sampleCandles())
        whenever(evaluatorBridge.evaluateBacktest(any(), any(), any()))
            .thenReturn(
                StrategyEvalResult(
                    signal = Signal.BUY,
                    strength = BigDecimal.ONE,
                    reason = mapOf("trigger" to "THRESHOLD", "lhs" to "close", "rhs" to "ma(10)"),
                    evaluatedAt = Clock.System.now(),
                    evalTimeMs = 1,
                ),
            )
        whenever(persister.persist(any())).thenAnswer { invocation ->
            captured = invocation.arguments[0] as ScenarioReplayResult
            sampleRunEntity()
        }
        whenever(queryService.listEvents(100L, "74683223", 0, 500)).thenReturn(PageImpl(emptyList()))
        whenever(queryService.listPositions(100L, "74683223")).thenReturn(emptyList())

        service.runAndPersist(
            ScenarioReplayRunRequestDto(
                userId = "74683223",
                strategyId = 8L,
                code = "005930",
                interval = "1m",
                from = "2026-06-02T04:23:00Z",
                to = "2026-06-02T04:24:00Z",
                initialCash = BigDecimal.ZERO,
                initialPositions =
                    listOf(
                        ScenarioPositionSeedDto(
                            code = "005930",
                            quantity = 1,
                            averagePrice = BigDecimal("351500"),
                        ),
                    ),
            ),
        )

        val result = captured as ScenarioReplayResult.Success
        assertTrue(result.events.any { it.type.name == "SIGNAL_SELL" })
        assertTrue(result.events.any { it.type.name == "ORDER_FILLED" && it.payload["side"] == "SELL" })
        assertTrue(result.ledger.positions.none { it.code == "005930" && it.quantity > 0 })
    }

    private fun sampleStrategy(): Strategy {
        val buyAst = LiteralValue(BigDecimal.ONE)
        val sellAst = LiteralValue(BigDecimal.TEN)
        return Strategy(
            id = 8L,
            userId = 74683223L,
            definition =
                StrategyDefinition(
                    dsl =
                        DslStrategyDefinition(
                            name = "paper_learning_ma10_touch_1m_005930",
                            nameKo = "모의 학습용 1분봉 MA10 터치 삼성전자",
                            buyCondition = buyAst,
                            sellCondition = sellAst,
                        ),
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 1,
                    indicatorCallCount = 0,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
            codes = listOf("005930"),
            intervals = listOf("1m"),
        )
    }

    private fun sampleCandles(): List<CandleJpaEntity> =
        listOf(
            candle("2026-06-02T04:23:00Z", open = "351500", close = "351500"),
            candle("2026-06-02T04:24:00Z", open = "350500", close = "350500"),
        )

    private fun candle(
        ts: String,
        open: String,
        close: String,
    ): CandleJpaEntity =
        CandleJpaEntity(
            code = "005930",
            interval = "1m",
            ts = Instant.parse(ts),
            openRaw = BigDecimal(open),
            highRaw = BigDecimal(open),
            lowRaw = BigDecimal(close),
            closeRaw = BigDecimal(close),
            volume = 1L,
            source = "TEST",
        )

    private fun sampleRunEntity(): ScenarioRunJpaEntity =
        ScenarioRunJpaEntity(
            id = 100L,
            userId = "74683223",
            strategyId = 8L,
            strategyName = "모의 학습용 1분봉 MA10 터치 삼성전자",
            code = "005930",
            interval = "1m",
            rangeFrom = Instant.parse("2026-06-02T04:23:00Z"),
            rangeTo = Instant.parse("2026-06-02T04:24:00Z"),
            initialCash = BigDecimal.ZERO,
            fillModel = "NEXT_CANDLE_OPEN",
            status = "SUCCESS",
            eventCount = 6,
            finalCash = BigDecimal("350500"),
            startedAt = Instant.parse("2026-06-02T04:23:00Z"),
            completedAt = Instant.parse("2026-06-02T04:24:00Z"),
        )
}

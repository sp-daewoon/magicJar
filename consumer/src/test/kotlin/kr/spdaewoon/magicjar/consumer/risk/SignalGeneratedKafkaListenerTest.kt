package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService
import kr.spdaewoon.magicjar.consumer.order.KisExecutionSyncResult
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskNotifier
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.support.Acknowledgment
import java.math.BigDecimal

class SignalGeneratedKafkaListenerTest {
    private val objectMapper = ObjectMapper()
    private val riskConfigRepository = mockk<RiskConfigRepository>()
    private val riskEventRepository = mockk<RiskEventRepository>(relaxed = true)
    private val riskPositionPort = mockk<RiskPositionPort>()
    private val signalRiskStatusUpdater = mockk<SignalRiskStatusUpdater>(relaxed = true)
    private val riskNotifier = mockk<RiskNotifier>(relaxed = true)
    private val killSwitchQueryPort = mockk<KillSwitchQueryPort>()
    private val capitalSnapshotProvider = mockk<CapitalSnapshotProvider>()
    private val tradeFrequencyGuard = mockk<TradeFrequencyRedisGuard>(relaxed = true)
    private val dailyPnlCalculator = mockk<DailyPnlCalculator>()
    private val orderIntentKafkaProducer = mockk<OrderIntentKafkaProducer>(relaxed = true)
    private val riskViolationKafkaProducer = mockk<RiskViolationKafkaProducer>(relaxed = true)
    private val autoPauseGuard = mockk<AutoPauseGuard>(relaxed = true)
    private val executionBackfillService = mockk<KisExecutionBackfillService>(relaxed = true)
    private val orderRequestRepository = mockk<OrderRequestRepository>()
    private val strategyLookupPort = mockk<StrategyLookupPort>()
    private val kisBalanceQueryPort = mockk<KisBalanceQueryPort>()
    private val now = Instant.parse("2026-05-22T01:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    @Test
    fun `BUY ALLOWED — risk budget 기반 주문 수량을 계산해서 order intent에 싣는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 20) }
    }

    @Test
    fun `KillSwitch ON — risk 단계에서 BLOCKED 처리하고 order intent를 발행하지 않는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns true
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener().onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.KILL_SWITCH_ON, match { it.contains("KillSwitch") })
        }
        verify(exactly = 1) { riskViolationKafkaProducer.publish(any(), match { it.rule == RiskRule.KILL_SWITCH_ON }, any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
        verify(exactly = 0) { autoPauseGuard.checkAndPauseIfNeeded(any(), any()) }
    }

    @Test
    fun `KillSwitchQueryPort 미합류 — fail closed로 BLOCKED 처리한다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(killSwitchProvider = nullableProvider())
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.KILL_SWITCH_ON, match { it.contains("KillSwitch") })
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
        verify(exactly = 0) { autoPauseGuard.checkAndPauseIfNeeded(any(), any()) }
    }

    @Test
    fun `SELL ALLOWED — 보유 수량 전체를 주문 수량으로 사용한다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7) }
    }

    @Test
    fun `SELL BLOCKED — 내부 보유가 있어도 KIS 잔고에 없으면 주문 의도를 발행하지 않는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(kisBalance = sampleKisBalance(quantity = 0))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.SELL_QUANTITY_UNAVAILABLE, match { it.contains("KIS_HOLDING_QTY") })
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `SELL BLOCKED — KIS 잔고 조회 실패는 주문 전 차단하되 전략 auto-pause 누적은 제외한다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }
        every { kisBalanceQueryPort.fetchFullBalance(any(), any()) } throws IllegalStateException("EGW00201 초당 거래건수를 초과하였습니다")

        listener(kisBalance = null)
            .onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.SELL_QUANTITY_UNAVAILABLE,
                match { it.contains("KIS_BALANCE_LOOKUP_FAILED") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
        verify(exactly = 0) { autoPauseGuard.checkAndPauseIfNeeded(any(), any()) }
    }

    @Test
    fun `BUY BLOCKED — RiskPlan 없는 전략은 risk context 구성 전 차단한다`() {
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(strategies = listOf(sampleStrategy(stopLossPct = null, takeProfitPct = null)))
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.RISK_PLAN_REQUIRED, match { it.contains("MISSING_STOP") })
        }
        verify(exactly = 0) { riskPositionPort.findCurrentByUser(any()) }
        verify(exactly = 0) { capitalSnapshotProvider.getCapital(any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `BUY ALLOWED — 단타형 RiskPlan은 익절 기준이 손절 기준 이하라도 차단하지 않는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(strategies = listOf(sampleStrategy(stopLossPct = BigDecimal("5.00"), takeProfitPct = BigDecimal("3.00"))))
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 20) }
    }

    @Test
    fun `REAL 모드 KIS 체결 sync 실패 — risk 단계에서 BLOCKED 처리하고 order intent를 발행하지 않는다`() {
        every { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.REAL) } returns
            KisExecutionSyncResult.failure(
                userId = "user-A",
                accountType = AccountType.REAL,
                error = "KIS_FETCH_FAILED:timeout",
            )
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(
            magicjarMode = "REAL",
            executionBackfillProvider = providerOf(executionBackfillService),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.KIS_EXECUTION_SYNC_STALE, match { it.contains("KIS_EXECUTION_SYNC_STALE") })
        }
        verify(exactly = 1) {
            riskViolationKafkaProducer.publish(any(), match { it.rule == RiskRule.KIS_EXECUTION_SYNC_STALE }, any())
        }
        verify(exactly = 0) { dailyPnlCalculator.getDailyPnl(any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `REAL 모드 KIS sync 후 daily PnL 조회 실패도 BLOCKED 처리한다`() {
        every { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.REAL) } returns
            KisExecutionSyncResult(
                userId = "user-A",
                accountType = AccountType.REAL,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = java.time.Instant.parse("2026-05-22T01:00:00Z"),
            )
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } throws IllegalStateException("DB down")
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(
            magicjarMode = "REAL",
            executionBackfillProvider = providerOf(executionBackfillService),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.KIS_EXECUTION_SYNC_STALE, match { it.contains("DAILY_PNL_UNAVAILABLE") })
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    private fun listener(
        magicjarMode: String = "MOCK",
        executionBackfillProvider: ObjectProvider<KisExecutionBackfillService> = nullableProvider(),
        killSwitchProvider: ObjectProvider<KillSwitchQueryPort> = providerOf(killSwitchQueryPort),
        strategies: List<Strategy> = listOf(sampleStrategy()),
        kisBalance: KisAccountBalance? = sampleKisBalance(quantity = 100),
    ): SignalGeneratedKafkaListener {
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns strategies
        every { orderRequestRepository.findOpenByUserId(any()) } returns emptyList()
        if (kisBalance != null) {
            every { kisBalanceQueryPort.fetchFullBalance(any(), any()) } returns kisBalance
        }
        return SignalGeneratedKafkaListener(
            objectMapper = objectMapper,
            riskGatewayEvaluator = RiskGatewayEvaluator(),
            tradeFrequencyGuard = tradeFrequencyGuard,
            dailyPnlCalculator = dailyPnlCalculator,
            capitalSnapshotProvider = capitalSnapshotProvider,
            orderIntentKafkaProducer = orderIntentKafkaProducer,
            riskViolationKafkaProducer = riskViolationKafkaProducer,
            autoPauseGuard = autoPauseGuard,
            riskConfigRepositoryProvider = providerOf(riskConfigRepository),
            riskEventRepositoryProvider = providerOf(riskEventRepository),
            riskPositionPortProvider = providerOf(riskPositionPort),
            signalRiskStatusUpdaterProvider = providerOf(signalRiskStatusUpdater),
            riskNotifierProvider = providerOf(riskNotifier),
            killSwitchQueryPortProvider = killSwitchProvider,
            orderRequestRepositoryProvider = providerOf(orderRequestRepository),
            strategyLookupPortProvider = providerOf(strategyLookupPort),
            kisBalanceQueryPortProvider = providerOf(kisBalanceQueryPort),
            executionBackfillServiceProvider = executionBackfillProvider,
            magicjarMode = magicjarMode,
            clock = clock,
        )
    }

    private fun sampleConfig(): RiskConfig =
        RiskConfig(
            userId = "user-A",
            maxPositionAmount = BigDecimal("1000000"),
            maxPositionPercent = BigDecimal("10.00"),
            dailyLossLimitAmount = null,
            dailyLossLimitPercent = BigDecimal("3.00"),
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = now,
            updatedAt = now,
        )

    private fun sampleStrategy(
        stopLossPct: BigDecimal? = BigDecimal("5.00"),
        takeProfitPct: BigDecimal? = BigDecimal("10.00"),
    ): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                    fixedStopLoss = stopLossPct,
                    takeProfitPct = takeProfitPct,
                ),
            complexity =
                StrategyComplexity(
                    astDepth = 1,
                    nodeCount = 1,
                    indicatorCallCount = 0,
                    estimatedEvalTimeMs = 1,
                    complexityClass = ComplexityClass.LOW,
                ),
        )

    private fun sampleKisBalance(quantity: Int): KisAccountBalance =
        KisAccountBalance(
            userId = "user-A",
            accountType = AccountType.MOCK,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("10000000"),
                    cashD1 = BigDecimal("10000000"),
                    cashD2 = BigDecimal("10000000"),
                    totalEvaluation = BigDecimal("10000000"),
                    netAssetValue = BigDecimal("10000000"),
                    totalPurchaseAmount = BigDecimal.ZERO,
                    totalHoldingEvaluation = BigDecimal.ZERO,
                    totalProfitLoss = BigDecimal.ZERO,
                    fetchedAt = java.time.Instant.parse("2026-05-22T01:00:00Z"),
                ),
            holdings =
                if (quantity > 0) {
                    listOf(
                        AccountHolding(
                            code = "005930",
                            name = "삼성전자",
                            quantity = quantity,
                            avgPrice = BigDecimal("70000"),
                            currentPrice = BigDecimal("72000"),
                            evaluationAmount = BigDecimal("504000"),
                            profitLoss = BigDecimal("14000"),
                            profitLossRate = BigDecimal("2.00"),
                        ),
                    )
                } else {
                    emptyList()
                },
            fetchedAt = java.time.Instant.parse("2026-05-22T01:00:00Z"),
        )

    private fun payload(
        side: String,
        price: String,
    ): String =
        objectMapper.writeValueAsString(
            mapOf(
                "id" to 77,
                "strategyId" to 100,
                "userId" to "user-A",
                "code" to "005930",
                "interval" to CandleInterval.FIVE_MIN.code,
                "ts" to now.epochSeconds,
                "side" to side,
                "strength" to 80,
                "reason" to "test",
                "priceAtSignal" to price,
            ),
        )

    private fun record(value: String) = ConsumerRecord("signal.generated.v1", 0, 0L, "user-A", value)

    private fun ack(): Acknowledgment = mockk(relaxed = true)

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }

    private inline fun <reified T : Any> nullableProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns null
        }
}

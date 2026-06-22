package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.autopilot.AutopilotBuyGuard
import kr.spdaewoon.magicjar.consumer.autopilot.AutopilotKillSwitch
import kr.spdaewoon.magicjar.consumer.autopilot.AutopilotStateStore
import kr.spdaewoon.magicjar.consumer.autopilot.MagicjarAutopilotProperties
import kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService
import kr.spdaewoon.magicjar.consumer.order.KisExecutionSyncResult
import kr.spdaewoon.magicjar.consumer.order.OrderCancelledKafkaProducer
import kr.spdaewoon.magicjar.consumer.order.RestingSellOrderCanceller
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderRequest
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.PaperCapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskEventRepository
import kr.spdaewoon.magicjar.domain.risk.port.RiskNotifier
import kr.spdaewoon.magicjar.domain.risk.port.RiskPositionPort
import kr.spdaewoon.magicjar.domain.risk.port.SignalRiskStatusUpdater
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.port.StrategyLookupPort
import kr.spdaewoon.magicjar.domain.user.AccountType
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import org.springframework.kafka.support.Acknowledgment
import java.math.BigDecimal

/**
 * P0 — magicjar-autopilot 하드 칭(자본 한도) **fail-closed wiring 회귀 테스트** (2026-06-22 라이브 우회 incident).
 *
 * ## 라이브 incident (2026-06-22 장중)
 * owner=AUTOPILOT BUY 가 [SignalGeneratedKafkaListener.applyAutopilotBuyGuard] 의 [AutopilotBuyGuard.evaluate] 를
 * 한 번도 거치지 않고(평가 로그 0건) 풀 한도의 14배(노출 363k / 풀 26k)를 실매수했다. 근본은 owner 확정 lookup
 * 비대칭(`findActiveByCodeAndInterval` 단독)으로 strategy=null → isAutopilotBuy=false → 무게이트 Proceed 였다.
 *
 * ## 봉인 불변식 (코드 강제 > 프롬프트)
 * **"Guard 가 owner=AUTOPILOT BUY 를 평가하지 못하는 모든 경우 = Reject."**
 *  - Guard provider null + enabled=true → Reject(과거 무조건 Proceed = fail-OPEN 회귀 검출).
 *  - lookup null/owner 미상 + enabled=true → Reject.
 *  - 풀 한도 초과 매수(라이브 363k vs 26k 재현) → Reject.
 *  - price<=0 + owner=AUTOPILOT → Reject(사이징 불가 시 안 산다).
 *  - **USER BUY 는 무영향**(zero-regression — 후성·사용자 매매 byte 동일).
 */
class AutopilotBuyGuardWiringFailClosedTest {
    private val objectMapper = ObjectMapper()
    private val riskConfigRepository = mockk<RiskConfigRepository>()
    private val riskEventRepository = mockk<RiskEventRepository>(relaxed = true)
    private val riskPositionPort = mockk<RiskPositionPort>()
    private val strategyPositionPort = mockk<StrategyPositionPort>(relaxed = true)
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
    private val kisOrderPort = mockk<KisOrderPort>(relaxed = true)
    private val orderCancelledKafkaProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val restingSellOrderCanceller = RestingSellOrderCanceller(kisOrderPort, orderCancelledKafkaProducer)
    private val entryQuotePort = mockk<EntryQuotePort>()
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    // autopilot Guard 협력자 — 실제 Guard 인스턴스(stub 아님)로 풀/캡 산정 로직까지 통과시켜야 라이브 재현이 유효하다.
    private val stateStore = mockk<AutopilotStateStore>()
    private val strategyPositionRepository = mockk<StrategyPositionJpaRepository>()
    private val killSwitch = mockk<AutopilotKillSwitch>(relaxed = true)

    private val now = Instant.parse("2026-06-22T01:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    // ─────────────────────────────────────────────────────────────────────────
    // 1) Guard provider null + autopilot enabled → owner=AUTOPILOT BUY Reject (과거 Proceed 회귀)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `Guard provider null + enabled=true + AUTOPILOT BUY 는 Reject (fail-OPEN 회귀 검출)`() {
        commonAllowStubs()
        stubAutopilotStrategyLookup()

        listener(
            autopilotGuardProvider = nullableProvider(),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "37250")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.POSITION_LIMIT,
                match { it.contains("AUTOPILOT_GUARD_UNAVAILABLE") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `Guard provider null + enabled=false + USER BUY 는 Proceed (zero-regression)`() {
        commonAllowStubs()
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())

        listener(
            autopilotGuardProvider = nullableProvider(),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = false)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // USER 경로는 게이트 미적용 — 정상 발행(autopilot blocked 0).
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2) lookup null/owner 미상 + autopilot enabled → Reject
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `lookup owner 미상(empty) + enabled=true + BUY 는 Reject (owner 판정 불가 보수 차단)`() {
        commonAllowStubs()
        // active 엔 USER 존재(신호 생성 경로) → 평가 통과. 그러나 Guard owner 확정 lookup(findSellEval)은 빈 → owner 미상.
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns emptyList()

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "37250")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.POSITION_LIMIT,
                match { it.contains("AUTOPILOT_OWNER_UNKNOWN") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
    }

    @Test
    fun `lookup owner 미상(empty) + enabled=false + BUY 는 Proceed (USER 단정 zero-regression)`() {
        commonAllowStubs()
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns emptyList()

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = false)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = false)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3) 풀 한도 초과 매수 → Reject (라이브 363k vs 풀 26k 재현)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `풀 한도 소진 AUTOPILOT BUY 는 Reject (라이브 노출363k vs 풀 재현)`() {
        // 라이브 재현: autopilot 루프 노출이 이미 풀 한도를 넘었는데(363k) 추가 BUY 가 들어온 상황.
        //   일반 RiskConfig budget 은 순자산 1억 기준 통과(resolvedQuantity≥1 보장 — guard 도달 전 INVALID_ORDER_INTENT 회피).
        //   그러나 autopilot 풀(순자산×poolPct 5% = 500만)은 loopExposure 363만이 이미 점유 → 추가 제안이 풀 잔여 초과 → Guard Reject.
        commonAllowStubs(netWorth = "100000000")
        stubAutopilotStrategyLookup()
        every { stateStore.load() } returns AutopilotState()
        // loopExposure 5,000,000(=풀 100% 소진) → 어떤 추가 제안도 풀 잔여 0 초과 → Reject(건당 캡·풀 잔여 둘 다 위반).
        every { strategyPositionRepository.sumAutopilotExposureByUser(any()) } returns BigDecimal("5000000")
        every { strategyPositionRepository.countAutopilotHeldCodesByUser(any()) } returns 1

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "325750")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.POSITION_LIMIT,
                match { it.contains("AUTOPILOT_GUARD_REJECT") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 4) price<=0 + AUTOPILOT → 실주문 0 (사이징 불가)
    //   진단: price<=0 은 riskGatewayEvaluator 가 INVALID_SIGNAL_PRICE 로 먼저 차단 → applyAutopilotBuyGuard 에
    //   도달하지 않는다(상위 게이트가 이미 봉인). 따라서 실주문 0 은 INVALID_SIGNAL_PRICE 로 보장된다.
    //   guard 내 price<=0 Reject 분기는 그 상위 게이트가 미래에 변형돼도 무너지지 않게 하는 방어 심층(belt-and-suspenders).
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `price 0 + AUTOPILOT BUY 는 실주문 0 (INVALID_SIGNAL_PRICE 상위 차단)`() {
        commonAllowStubs()
        stubAutopilotStrategyLookup()

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "0")), ack())

        // 핵심 불변식: price<=0 AUTOPILOT BUY 는 실주문 0(어떤 분모 없는 사이징으로도 매수 안 함).
        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.INVALID_SIGNAL_PRICE, any())
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 5) Guard 정상 주입 + 한도 내 → Allow (기존 정상 경로 보존)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `Guard 정상 + 한도 내 AUTOPILOT BUY 는 Allow 발행`() {
        // 순자산 1억 → 풀 5%=500만, 건당 캡 20%=100만. 제안 1주 @50,000 → 한도 내 → Allow → 발행.
        commonAllowStubs(netWorth = "100000000")
        stubAutopilotStrategyLookup()
        every { stateStore.load() } returns AutopilotState()
        every { strategyPositionRepository.sumAutopilotExposureByUser(any()) } returns BigDecimal.ZERO
        every { strategyPositionRepository.countAutopilotHeldCodesByUser(any()) } returns 0

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6) USER BUY 는 Guard enabled=true 여도 무영향 (zero-regression 핵심)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `USER BUY 는 enabled=true Guard 주입돼도 Proceed (byte 동일)`() {
        commonAllowStubs(netWorth = "100000000")
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns listOf(userStrategy())

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // fixtures / helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun realGuard(enabled: Boolean): AutopilotBuyGuard =
        AutopilotBuyGuard(
            properties = MagicjarAutopilotProperties(enabled = enabled),
            stateStore = stateStore,
            strategyPositionRepository = strategyPositionRepository,
            killSwitch = killSwitch,
        )

    /** ALLOWED 까지 통과시키는 공통 risk stub(KillSwitch off·capital·positions·freq). */
    private fun commonAllowStubs(netWorth: String = "100000000") {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal(netWorth))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }
    }

    /** Guard owner 확정 lookup(findSellEval) + 신호 생성 lookup(findActive) 둘 다 AUTOPILOT 전략 반환. */
    private fun stubAutopilotStrategyLookup() {
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())
    }

    private fun autopilotStrategy(): Strategy = strategy(StrategyOwner.AUTOPILOT)

    private fun userStrategy(): Strategy = strategy(StrategyOwner.USER)

    private fun strategy(owner: StrategyOwner): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            owner = owner,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "test", buyCondition = null),
                    fixedStopLoss = BigDecimal("5.00"),
                    takeProfitPct = BigDecimal("10.00"),
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

    private fun sampleConfig(): RiskConfig =
        RiskConfig(
            userId = "user-A",
            maxPositionAmount = BigDecimal("100000000"),
            maxPositionPercent = BigDecimal("100.00"),
            dailyLossLimitAmount = null,
            dailyLossLimitPercent = BigDecimal("3.00"),
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = now,
            updatedAt = now,
        )

    private fun sampleKisBalance(): KisAccountBalance =
        KisAccountBalance(
            userId = "user-A",
            accountType = AccountType.MOCK,
            balance =
                AccountBalance(
                    cashBalance = BigDecimal("100000000"),
                    cashD1 = BigDecimal("100000000"),
                    cashD2 = BigDecimal("100000000"),
                    totalEvaluation = BigDecimal("100000000"),
                    netAssetValue = BigDecimal("100000000"),
                    totalPurchaseAmount = BigDecimal.ZERO,
                    totalHoldingEvaluation = BigDecimal.ZERO,
                    totalProfitLoss = BigDecimal.ZERO,
                    fetchedAt = java.time.Instant.parse("2026-06-22T01:00:00Z"),
                ),
            holdings = emptyList(),
            fetchedAt = java.time.Instant.parse("2026-06-22T01:00:00Z"),
        )

    private fun listener(
        autopilotGuardProvider: ObjectProvider<AutopilotBuyGuard>,
        autopilotPropertiesProvider: ObjectProvider<MagicjarAutopilotProperties>,
    ): SignalGeneratedKafkaListener {
        every { orderRequestRepository.findOpenByUserId(any()) } returns emptyList<OrderRequest>()
        every { kisBalanceQueryPort.fetchFullBalance(any(), any()) } returns sampleKisBalance()
        every { entryQuotePort.latestQuote(any()) } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49950"), askQty1 = 100L, asOf = now)
        val entryOrderPriceResolver =
            EntryOrderPriceResolver(
                entryQuotePortProvider = providerOf(entryQuotePort),
                // MARKET 경로(limit-enabled=false) — 본 테스트는 게이트 차단/통과 검증이라 가격 산정은 MARKET 으로 단순화.
                limitEnabled = false,
                clock = clock,
            )
        return SignalGeneratedKafkaListener(
            objectMapper = objectMapper,
            riskGatewayEvaluator = RiskGatewayEvaluator(),
            tradeFrequencyGuard = tradeFrequencyGuard,
            dailyPnlCalculator = dailyPnlCalculator,
            capitalSnapshotProvider = capitalSnapshotProvider,
            paperCapitalSnapshotProviderProvider = nullableProvider<PaperCapitalSnapshotProvider>(),
            orderIntentKafkaProducer = orderIntentKafkaProducer,
            riskViolationKafkaProducer = riskViolationKafkaProducer,
            autoPauseGuard = autoPauseGuard,
            riskConfigRepositoryProvider = providerOf(riskConfigRepository),
            riskEventRepositoryProvider = providerOf(riskEventRepository),
            riskPositionPortProvider = providerOf(riskPositionPort),
            strategyPositionPortProvider = providerOf(strategyPositionPort),
            signalRiskStatusUpdaterProvider = providerOf(signalRiskStatusUpdater),
            riskNotifierProvider = providerOf(riskNotifier),
            killSwitchQueryPortProvider = providerOf(killSwitchQueryPort),
            orderRequestRepositoryProvider = providerOf(orderRequestRepository),
            strategyLookupPortProvider = providerOf(strategyLookupPort),
            kisBalanceQueryPortProvider = providerOf(kisBalanceQueryPort),
            executionBackfillServiceProvider = passingSyncProvider(),
            entryOrderPriceResolver = entryOrderPriceResolver,
            restingSellOrderCanceller = restingSellOrderCanceller,
            systemEventLoggerProvider = providerOf(systemEventLogger),
            autopilotBuyGuardProvider = autopilotGuardProvider,
            autopilotPropertiesProvider = autopilotPropertiesProvider,
            magicjarMode = "MOCK",
            clock = clock,
        )
    }

    private fun payload(
        side: String,
        price: String,
    ): String =
        objectMapper.writeValueAsString(
            buildMap<String, Any?> {
                put("id", 77)
                put("strategyId", 100)
                put("userId", "user-A")
                put("code", "005930")
                put("interval", CandleInterval.FIVE_MIN.code)
                put("ts", now.epochSeconds)
                put("side", side)
                put("strength", 80)
                put("reason", "test")
                put("priceAtSignal", price)
            },
        )

    private fun record(value: String) = ConsumerRecord(KafkaTopics.Risk.SIGNAL_GENERATED, 0, 0L, "user-A", value)

    private fun ack(): Acknowledgment = mockk(relaxed = true)

    private fun passingSyncProvider(): ObjectProvider<KisExecutionBackfillService> {
        every { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.MOCK) } returns
            KisExecutionSyncResult(
                userId = "user-A",
                accountType = AccountType.MOCK,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = java.time.Instant.parse("2026-06-22T01:00:00Z"),
            )
        return providerOf(executionBackfillService)
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns value
        }

    private inline fun <reified T : Any> nullableProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also {
            every { it.getIfAvailable() } returns null
        }
}

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
 * 비대칭(신규 BUY·보유 0 에서 lookup null)으로 strategy=null → isAutopilotBuy=false → 무게이트 Proceed 였다.
 *
 * ## 견고 해법 — owner-on-signal (lookup 제거, 2026-06-22 P0)
 * 신호가 [kr.spdaewoon.magicjar.domain.signal.Signal.strategyOwner] 로 owner 를 운반한다 → Guard 가 lookup 없이
 * payload owner 로 직접 식별 → 신규 BUY race 원천 제거. lookup 은 owner 필드 없는 **legacy 신호(null)** fallback 만.
 *
 * ## 봉인 불변식 (코드 강제 > 프롬프트)
 * **"Guard 가 owner=AUTOPILOT BUY 를 평가하지 못하는 모든 경우 = Reject."**
 *  - 신호 운반 owner=AUTOPILOT + Guard provider null + enabled=true → Reject(과거 무조건 Proceed = fail-OPEN 회귀 검출).
 *  - legacy 신호(owner null) + lookup null/owner 미상 + enabled=true → Reject.
 *  - 풀 한도 초과 매수(라이브 363k vs 26k 재현) → Reject.
 *  - price<=0 + owner=AUTOPILOT → Reject(사이징 불가 시 안 산다).
 *  - **신호 운반 owner=USER BUY 는 무영향**(zero-regression — 후성·사용자 매매 byte 동일·lookup 0).
 *  - **legacy 신호 owner-on-signal 하위호환** — owner 필드 없는 payload 도 lookup fallback 으로 정상 식별.
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
    // 1) Guard provider null + autopilot enabled → 신호 운반 owner=AUTOPILOT BUY Reject (과거 Proceed 회귀)
    //    ★ lookup stub 없음 — owner 가 신호 payload 로 운반되므로 lookup 불필요(견고 fix 핵심 증명).
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `Guard provider null + enabled=true + 운반owner=AUTOPILOT BUY 는 Reject (fail-OPEN 회귀 검출, lookup 0)`() {
        commonAllowStubs()
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())

        listener(
            autopilotGuardProvider = nullableProvider(),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "37250", owner = "AUTOPILOT")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.POSITION_LIMIT,
                match { it.contains("AUTOPILOT_GUARD_UNAVAILABLE") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
        // 견고 fix 증명 — owner 식별에 Guard owner 확정 lookup(findSellEval)을 쓰지 않는다.
        verify(exactly = 0) { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) }
    }

    @Test
    fun `Guard provider null + enabled=false + 운반owner=USER BUY 는 Proceed (zero-regression, lookup 0)`() {
        commonAllowStubs()
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())

        listener(
            autopilotGuardProvider = nullableProvider(),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = false)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000", owner = "USER")), ack())

        // USER 경로는 게이트 미적용 — 정상 발행(autopilot blocked 0).
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
        // 운반 owner=USER 면 lookup 자체가 불필요.
        verify(exactly = 0) { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2) legacy 신호(owner 필드 없음) + lookup null/owner 미상 + enabled → Reject (하위호환 fallback)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `legacy owner null + lookup 미상(empty) + enabled=true + BUY 는 Reject (owner 판정 불가 보수 차단)`() {
        commonAllowStubs()
        // active 엔 USER 존재(신호 생성 경로) → 평가 통과. 그러나 Guard owner 확정 lookup(findSellEval)은 빈 → owner 미상.
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns emptyList()

        // owner 미운반(legacy) → strategyOwner=null → lookup fallback 진입.
        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "37250", owner = null)), ack())

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
    fun `legacy owner null + lookup 미상(empty) + enabled=false + BUY 는 Proceed (USER 단정 zero-regression)`() {
        commonAllowStubs()
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns emptyList()

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = false)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = false)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000", owner = null)), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 2-b) legacy 신호(owner 필드 없음) + lookup 으로 AUTOPILOT 확정 → 정상 Guard 평가 (하위호환 fallback 정상 식별)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `legacy owner null + lookup AUTOPILOT 확정 + 풀 소진 → Reject (fallback 정상 식별)`() {
        commonAllowStubs(netWorth = "100000000")
        stubAutopilotStrategyLookup()
        every { stateStore.load() } returns AutopilotState()
        every { strategyPositionRepository.sumAutopilotExposureByUser(any()) } returns BigDecimal("5000000")
        every { strategyPositionRepository.countAutopilotHeldCodesByUser(any()) } returns 1

        // owner 미운반(legacy) → lookup fallback 이 AUTOPILOT 확정 → Guard.evaluate 도달 → 풀 소진 Reject.
        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "325750", owner = null)), ack())

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
    // 3) 풀 한도 초과 매수(보유 有) → Reject (라이브 363k vs 풀 26k 재현)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `풀 한도 소진(보유 有) 운반owner=AUTOPILOT BUY 는 Reject (라이브 노출363k vs 풀 재현)`() {
        // 라이브 재현: autopilot 루프 노출이 이미 풀 한도를 넘었는데(363k) 추가 BUY 가 들어온 상황(보유 種목 1).
        //   일반 RiskConfig budget 은 순자산 1억 기준 통과(resolvedQuantity≥1 보장 — guard 도달 전 INVALID_ORDER_INTENT 회피).
        //   그러나 autopilot 풀(순자산×poolPct 5% = 500만)은 loopExposure 363만이 이미 점유 → 추가 제안이 풀 잔여 초과 → Guard Reject.
        commonAllowStubs(netWorth = "100000000")
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())
        every { stateStore.load() } returns AutopilotState()
        // loopExposure 5,000,000(=풀 100% 소진) + 보유 1종목 → 어떤 추가 제안도 풀 잔여 0 초과 → Reject(건당 캡·풀 잔여 둘 다 위반).
        every { strategyPositionRepository.sumAutopilotExposureByUser(any()) } returns BigDecimal("5000000")
        every { strategyPositionRepository.countAutopilotHeldCodesByUser(any()) } returns 1

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "325750", owner = "AUTOPILOT")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.POSITION_LIMIT,
                match { it.contains("AUTOPILOT_GUARD_REJECT") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        // 견고 fix 증명 — owner 식별에 lookup 미사용.
        verify(exactly = 0) { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 3-b) ★ 신규 BUY(보유 0) 운반owner=AUTOPILOT → Guard.evaluate 도달 (라이브 우회 root 직접 봉인)
    //   라이브 incident 의 핵심: 신규 매수(보유 0)는 position-driven lookup 이 null → owner 미상 → 무게이트였다.
    //   owner-on-signal 로 보유 0 에서도 Guard.evaluate 가 반드시 돈다. 누적손실 한도 도달 → KillTriggered → kill ON.
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `신규BUY 보유0 운반owner=AUTOPILOT + 누적손실 한도 도달 → KillTriggered + kill ON (회로차단기 복구)`() {
        commonAllowStubs(netWorth = "100000000")
        // 보유 0(신규 BUY) — lookup 이 과거 null 을 반환하던 바로 그 케이스. owner 는 신호가 운반.
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())
        // 풀=500만, 누적손실 한도=풀50%=250만. cumRealizedLoss 250만 → KillTriggered.
        every { stateStore.load() } returns AutopilotState(cumRealizedLoss = BigDecimal("2500000"))
        every { strategyPositionRepository.sumAutopilotExposureByUser(any()) } returns BigDecimal.ZERO
        every { strategyPositionRepository.countAutopilotHeldCodesByUser(any()) } returns 0 // 보유 0

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000", owner = "AUTOPILOT")), ack())

        // KillTriggered → kill switch 발동(누적손실 회로차단기 복구 — 라이브 미작동의 정정).
        verify(exactly = 1) { killSwitch.trigger(any()) }
        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.KILL_SWITCH_ON,
                match { it.contains("AUTOPILOT_KILL") },
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
    fun `price 0 + 운반owner=AUTOPILOT BUY 는 실주문 0 (INVALID_SIGNAL_PRICE 상위 차단)`() {
        commonAllowStubs()
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "0", owner = "AUTOPILOT")), ack())

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
    fun `Guard 정상 + 한도 내(보유 有) 운반owner=AUTOPILOT BUY 는 Allow 발행`() {
        // 순자산 1억 → 풀 5%=500만, 건당 캡 20%=100만. 제안 1주 @50,000 → 한도 내 → Allow → 발행. 보유 1종목.
        commonAllowStubs(netWorth = "100000000")
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(autopilotStrategy())
        every { stateStore.load() } returns AutopilotState()
        every { strategyPositionRepository.sumAutopilotExposureByUser(any()) } returns BigDecimal("100000")
        every { strategyPositionRepository.countAutopilotHeldCodesByUser(any()) } returns 1

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000", owner = "AUTOPILOT")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 6) 운반owner=USER BUY 는 Guard enabled=true 여도 무영향 (zero-regression 핵심 · lookup 0)
    // ─────────────────────────────────────────────────────────────────────────
    @Test
    fun `운반owner=USER BUY 는 enabled=true Guard 주입돼도 Proceed (byte 동일, lookup 0)`() {
        commonAllowStubs(netWorth = "100000000")
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns listOf(userStrategy())

        listener(
            autopilotGuardProvider = providerOf(realGuard(enabled = true)),
            autopilotPropertiesProvider = providerOf(MagicjarAutopilotProperties(enabled = true)),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000", owner = "USER")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.POSITION_LIMIT, match { it.contains("AUTOPILOT") })
        }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        // 운반 owner=USER → lookup 미사용(견고 fix·zero-regression).
        verify(exactly = 0) { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) }
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

    /**
     * @param owner 신호가 운반하는 owner enum 이름(AUTOPILOT/USER/SYSTEM). null 이면 키 자체를 싣지 않아 **legacy 신호**
     *   (owner 필드 없는 payload) 하위호환 경로를 재현한다 → 역직렬화는 strategyOwner=null → 호출자 lookup fallback.
     */
    private fun payload(
        side: String,
        price: String,
        owner: String? = null,
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
                if (owner != null) put("strategyOwner", owner)
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

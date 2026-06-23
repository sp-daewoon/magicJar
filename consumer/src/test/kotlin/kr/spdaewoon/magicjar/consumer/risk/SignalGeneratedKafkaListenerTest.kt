package kr.spdaewoon.magicjar.consumer.risk

import com.fasterxml.jackson.databind.ObjectMapper
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.order.KisExecutionBackfillService
import kr.spdaewoon.magicjar.consumer.order.KisExecutionSyncResult
import kr.spdaewoon.magicjar.consumer.order.OrderCancelledKafkaProducer
import kr.spdaewoon.magicjar.consumer.order.RestingSellOrderCanceller
import kr.spdaewoon.magicjar.domain.account.AccountBalance
import kr.spdaewoon.magicjar.domain.account.AccountHolding
import kr.spdaewoon.magicjar.domain.account.KisAccountBalance
import kr.spdaewoon.magicjar.domain.account.port.KisBalanceQueryPort
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
import kr.spdaewoon.magicjar.domain.order.execution.port.KillSwitchQueryPort
import kr.spdaewoon.magicjar.domain.order.execution.port.OrderRequestRepository
import kr.spdaewoon.magicjar.domain.position.Position
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.RiskEvent
import kr.spdaewoon.magicjar.domain.risk.RiskRule
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
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
    private val strategyPositionPort = mockk<kr.spdaewoon.magicjar.domain.position.port.StrategyPositionPort>(relaxed = true)
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

    // 자본 path hotfix — resting auto-tp 선취소 공용 컴포넌트(watcher와 DRY). cancel 검증은 wrapped kisOrderPort로.
    private val kisOrderPort = mockk<kr.spdaewoon.magicjar.domain.order.execution.port.KisOrderPort>(relaxed = true)
    private val orderCancelledKafkaProducer = mockk<OrderCancelledKafkaProducer>(relaxed = true)
    private val restingSellOrderCanceller = RestingSellOrderCanceller(kisOrderPort, orderCancelledKafkaProducer)

    // ADR 0114 — BUY marketable limit 가격 산정 (진입 호가 source + system_event audit).
    private val entryQuotePort = mockk<EntryQuotePort>()
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)
    private val now = Instant.parse("2026-05-22T01:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    @Test
    fun `BUY ALLOWED — risk budget 기반 주문 수량을 계산해서 order intent에 싣는다 (limit-enabled opt-in → LIMIT)`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // 동작 변경 사유(hotfix 2026-06-08): entry_policy 없는 sampleStrategy는 default(limit-enabled=false)면 MARKET 경로다.
        //   marketable-limit LIMIT 발행을 검증하려면 전역 opt-in(entryLimitEnabled=true)이 필요하다.
        listener(entryLimitEnabled = true).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        // ADR 0114 — opt-in 시 BUY는 marketable limit. ask1=50000 ≤ cap(50000×1.002) → LIMIT 50000 발행.
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.LIMIT, BigDecimal("50000"))
        }
    }

    @Test
    fun `F10 — capital 조회 불가(Unavailable)는 0 fabricate·POSITION_LIMIT 위반 없이 시그널을 skip한다`() {
        // 14:12 incident 회귀: rate limit → capital 0 fabricate → "총자산 0원 × 70% = 한도 0원" POSITION_LIMIT 거짓 차단
        //   + risk_event·Slack 위반 알람·AutoPause 카운트 오염. 본 hotfix는 capital 조회 불가를 위반이 아니라 skip 으로 종결.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Unavailable("KIS_RATE_LIMIT")
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // 1) skip audit(SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE) 만 발사 — 위반 경로 진입 0.
        verify(exactly = 1) {
            systemEventLogger.emit(match { it.action == "SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE" })
        }
        // 2) risk_event INSERT·violation publish·AutoPause·markBlocked·주문발행 전부 0 (POSITION_LIMIT 위반 없음).
        verify(exactly = 0) { riskEventRepository.save(any()) }
        verify(exactly = 0) { riskViolationKafkaProducer.publish(any(), any(), any()) }
        verify(exactly = 0) { autoPauseGuard.checkAndPauseIfNeeded(any(), any()) }
        verify(exactly = 0) { signalRiskStatusUpdater.markBlocked(any(), any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 0) { signalRiskStatusUpdater.markAllowed(any()) }
        // capital 이 없으면 어차피 BUY skip 이므로 KIS 체결 backfill 도 태우지 않는다.
        verify(exactly = 0) { executionBackfillService.syncUserTodayExecutions(any(), any()) }
    }

    @Test
    fun `F10 — capital stale-cache(Available stale) 는 직전 잔고로 정상 평가한다 (skip 아님)`() {
        // 재시도 실패했지만 유효 TTL 내 직전 성공 잔고가 있으면 그 값으로 정상 룰 평가 — 0 차단·skip 둘 다 아님.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns
            CapitalOutcome.Available(BigDecimal("10000000"), stale = true)
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(entryLimitEnabled = true).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // stale 직전 잔고(1천만)로 정상 ALLOWED → 20주 발행. skip audit·위반 0.
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.LIMIT, BigDecimal("50000"))
        }
        verify(exactly = 0) { systemEventLogger.emit(match { it.action == "SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE" }) }
        verify(exactly = 0) { riskEventRepository.save(any()) }
    }

    @Test
    fun `F10 — SELL 은 capital 조회 불가여도 skip·차단 없이 청산을 진행한다 (capital 불요)`() {
        // SELL 은 청산·리스크 축소 액션 — 잔고조회 실패로 스킵/차단하면 포지션이 묶인다. capital 미상이어도 진행.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Unavailable("KIS_RATE_LIMIT")
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "SELL", price = "71000")), ack())

        // SELL 청산 정상 발행 — skip audit·위반 0.
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
        verify(exactly = 0) { systemEventLogger.emit(match { it.action == "SIGNAL_SKIPPED_CAPITAL_UNAVAILABLE" }) }
        verify(exactly = 0) { riskEventRepository.save(any()) }
    }

    @Test
    fun `incident 회귀 — RiskConfigRepository DB값(5M·70%)이 buildContext budget에 반영된다`() {
        // 2026-06-10 incident: consumer에 RiskConfigRepository 빈이 부재해 항상 defaultFor(1M·10%)만 평가 →
        //   사용자 DB risk_config(5M·70%)가 자동매매 경로에서 전면 무시되던 silent 배선 결함.
        //   본 회귀: DB값 5M/70%가 주입되면 budget = min(5M, 10M×70%=7M)=5M → 5M/50000=100주 발행.
        //   (기존 BUY ALLOWED 테스트의 1M/10%는 20주 — 동일 capital·price에서 config가 수량을 가른다.)
        every { riskConfigRepository.getOrDefault("user-A", now) } returns
            sampleConfig(maxPositionAmount = BigDecimal("5000000"), maxPositionPercent = BigDecimal("70.00"))
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(entryLimitEnabled = true).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        // 5M budget → 100주 (defaultFor 1M였다면 20주). DB값이 실제로 budget을 키웠음을 증명.
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 100, OrderPriceType.LIMIT, BigDecimal("50000"))
        }
    }

    @Test
    fun `incident 가드 — RiskConfigRepository 빈 부재(MOCK) → defaultFor(1M·10%) fallback (silent 아님)`() {
        // 빈 부재 시 buildContext가 defaultFor(1M/10%)로 fallback. MOCK은 graceful 진행하되 ERROR 로그로 가시화.
        // budget = min(1M, 10M×10%=1M)=1M → 1M/50000=20주.
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(entryLimitEnabled = true, riskConfigProvider = nullableProvider())
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // 빈 부재여도 MOCK은 defaultFor로 진행 → 20주. (RiskConfigRepository.getOrDefault 호출 0)
        verify(exactly = 0) { riskConfigRepository.getOrDefault(any(), any()) }
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.LIMIT, BigDecimal("50000"))
        }
    }

    @Test
    fun `incident 가드 — RiskConfigRepository 빈 부재(REAL) → fail-fast BLOCKED (자본 역방향 위험 차단)`() {
        // REAL 모드에서 빈 부재 시 defaultFor로 조용히 진행하면 사용자 좁은 한도를 무시하는 역방향 위험.
        // 다른 REAL 게이트(capital·positionPort)와 정합되게 RealRiskContextUnavailableException → BLOCKED.
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }
        // 공짜 룰 선차단(hotfix #5)은 config 빈 부재 시 defaultFor 로 통과 — KillSwitch off 로 buildContext fail-fast 까지 도달.
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(
            magicjarMode = "REAL",
            executionBackfillProvider = passingSyncProvider("REAL"),
            riskConfigProvider = nullableProvider(),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(
                77L,
                RiskRule.KIS_EXECUTION_SYNC_STALE,
                match { it.contains("RISK_CONFIG_REPO_UNAVAILABLE") },
            )
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `BUY ALLOWED — entry_policy 없는 전략 + limit-enabled false(default) → MARKET 발행 (자본 경로 회귀 정정)`() {
        // 회귀 핵심: 과거엔 entry_policy=null이어도 marketable-limit 적용 → 호가 미가용 종목 NO_QUOTE 스킵 → BUY 0.
        // 정정: entry_policy 없는 전략은 MARKET 발행으로 즉시 진입 복구(NO_QUOTE 스킵 0).
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // 호가가 없어도(미구독 종목) MARKET 경로는 영향 0 — 호가 조회조차 하지 않는다.
        listener(entryLimitEnabled = false, entryQuote = null)
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.MARKET, null)
        }
    }

    @Test
    fun `KillSwitch ON — risk 단계에서 BLOCKED 처리하고 order intent를 발행하지 않는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
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
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
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
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener().onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        // ADR 0114 §3-3·§5 — exit contextJson 부재(손절·일반 SELL) → MARKET(체결 확실성 우선, 하위호환).
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
    }

    @Test
    fun `SELL 익절(contextJson LIMIT) — LIMIT 발행 (ADR 0114 §3-3·§5)`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "LIMIT", "exitLimitPrice" to "73500"))
        listener().onSignalGenerated(record(payload(side = "SELL", price = "72000", contextJson = exitCtx)), ack())

        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.LIMIT, BigDecimal("73500"))
        }
    }

    @Test
    fun `SELL 익절 LIMIT 지정가 산정 실패(exitLimitPrice 누락) — MARKET fallback 발행 (F8)`() {
        // 2026-06-12 F8 · 최고 관리자 결재: 매도는 지정가를 못 구하면 거부하지 않고 시장가로 던진다(체결 보장 우선).
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // exitPriceType=LIMIT 의도였으나 exitLimitPrice 미산정(평단·트리거 가격 조회 실패) → MARKET fallback.
        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "LIMIT"))
        listener().onSignalGenerated(record(payload(side = "SELL", price = "72000", contextJson = exitCtx)), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), OrderPriceType.LIMIT, any()) }
    }

    @Test
    fun `SELL 익절 LIMIT 지정가 0(비정상) — MARKET fallback 발행 (F8)`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "LIMIT", "exitLimitPrice" to "0"))
        listener().onSignalGenerated(record(payload(side = "SELL", price = "72000", contextJson = exitCtx)), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
    }

    @Test
    fun `SELL 손절(contextJson MARKET) — MARKET 발행`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "MARKET"))
        listener().onSignalGenerated(record(payload(side = "SELL", price = "68000", contextJson = exitCtx)), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
    }

    @Test
    fun `SELL + resting auto-tp 존재 — 취소 선행 후 MARKET 발행 (먼저 오는 쪽이 이긴다)`() {
        sellContext()
        every { kisOrderPort.cancel(any(), any()) } returns
            kr.spdaewoon.magicjar.domain.order.execution.OrderResult
                .Right(Unit)

        listener(openOrders = listOf(restingAutoTp()))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        // resting auto-tp 취소 선행 → 그다음 MARKET SELL 발행.
        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
    }

    @Test
    fun `SELL + 수동 open SELL 존재 — fail-closed로 발행 중단 (취소 0)`() {
        sellContext()

        listener(openOrders = listOf(restingAutoTp(source = kr.spdaewoon.magicjar.domain.order.execution.OrderSource.MANUAL)))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
    }

    @Test
    fun `SELL + resting auto-tp ACK 미확정 — 발행 보류 (취소 0)`() {
        sellContext()

        listener(openOrders = listOf(restingAutoTp(ackPresent = false)))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
    }

    @Test
    fun `SELL 익절 LIMIT + 동일가 동일수량 auto-tp 이미 존재 — 발행 skip (중복 회피)`() {
        sellContext()

        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "LIMIT", "exitLimitPrice" to "73500"))
        listener(openOrders = listOf(restingAutoTp(quantity = 7, limitPrice = "73500")))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000", contextJson = exitCtx)), ack())

        // 동일가·동일수량 auto-tp 정합 → 취소 0 · 발행 0(coordinator ALREADY_ALIGNED 정합).
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
    }

    @Test
    fun `SELL 익절 LIMIT + 다른가 auto-tp 존재 — 취소 후 새 LIMIT 발행`() {
        sellContext()
        every { kisOrderPort.cancel(any(), any()) } returns
            kr.spdaewoon.magicjar.domain.order.execution.OrderResult
                .Right(Unit)

        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "LIMIT", "exitLimitPrice" to "73500"))
        listener(openOrders = listOf(restingAutoTp(quantity = 7, limitPrice = "73000")))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000", contextJson = exitCtx)), ack())

        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.LIMIT, BigDecimal("73500"))
        }
    }

    @Test
    fun `SELL ALLOW — 총보유 3 + 같은 전략 auto-tp 3 예약(보유 전량 잠금) → 선취소 후 발행 (KIS 가드 통과)`() {
        // 결함 재현 시나리오: auto-tp 익절 선주문이 KIS 매도가능수량을 0으로 잠가도, 그 선주문은 우리가
        // 발행 직전 취소해 풀어주므로 KIS 가드(총보유 − 수동·타전략예약)는 통과해야 한다.
        sellContext(positionQty = 3)
        every { kisOrderPort.cancel(any(), any()) } returns
            kr.spdaewoon.magicjar.domain.order.execution.OrderResult
                .Right(Unit)

        listener(
            kisBalance = sampleKisBalance(quantity = 3),
            openOrders = listOf(restingAutoTp(quantity = 3, limitPrice = "73500")),
        ).onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        // KIS 가드 통과(BLOCK 0) → resting auto-tp 선취소 → MARKET SELL 3주 발행.
        verify(exactly = 0) { signalRiskStatusUpdater.markBlocked(any(), RiskRule.SELL_QUANTITY_UNAVAILABLE, any()) }
        verify(exactly = 1) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 3, OrderPriceType.MARKET, null) }
    }

    @Test
    fun `SELL BLOCKED — 총보유 3 + 수동 SELL 3 예약 → 가용 0 차단 유지 (수동 침범 금지)`() {
        // 수동/타전략 open SELL은 우리가 취소 대상이 아니므로 예약 차감 유지 → 가용 0 → 차단(발행·취소 0).
        // (evaluator reservedSellQuantity가 KIS 가드보다 먼저 동일 결론을 내므로 발행 직전 KIS 가드까지 갈 일 없음.)
        sellContext(positionQty = 3)
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(
            kisBalance = sampleKisBalance(quantity = 3),
            openOrders =
                listOf(
                    restingAutoTp(
                        quantity = 3,
                        source = kr.spdaewoon.magicjar.domain.order.execution.OrderSource.MANUAL,
                    ),
                ),
        ).onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.SELL_QUANTITY_UNAVAILABLE, any())
        }
        verify(exactly = 0) { kisOrderPort.cancel(any(), any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
    }

    @Test
    fun `SELL BLOCKED — 내부 보유가 있어도 KIS 잔고에 없으면 주문 의도를 발행하지 않는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
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
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
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
        verify(exactly = 0) { capitalSnapshotProvider.capitalOutcome(any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `BUY ALLOWED — 단타형 RiskPlan은 익절 기준이 손절 기준 이하라도 차단하지 않는다`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // entry pricing은 본 케이스 관심사가 아님(RiskPlan 통과 검증) — opt-in으로 LIMIT 경로 고정해 발행만 확인.
        listener(
            strategies = listOf(sampleStrategy(stopLossPct = BigDecimal("5.00"), takeProfitPct = BigDecimal("3.00"))),
            entryLimitEnabled = true,
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 1) {
            orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.LIMIT, BigDecimal("50000"))
        }
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
        // canary D2 hotfix #5 — 공짜 룰 선차단이 KIS sync 보다 먼저 돌므로 KillSwitch 는 off 여야 sync 차단까지 도달.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

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
    fun `MOCK 모드 — KIS 체결 sync가 MOCK account type으로 실행된다 (REAL-only 게이트 제거 · 자본 정합 hotfix)`() {
        // 결함 재현: 과거 ensureKisExecutionSyncIfReal는 MOCK 모드면 sync 자체를 skip(체결 backfill 0)했다.
        // 정정: MOCK 모드도 sync 실행 + account type=MOCK 전달(VPS 페이퍼 계좌 당일 체결 조회 → ACK 정체 해소).
        every { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.MOCK) } returns
            KisExecutionSyncResult(
                userId = "user-A",
                accountType = AccountType.MOCK,
                fetched = 1,
                inserted = 1,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = java.time.Instant.parse("2026-05-22T01:00:00Z"),
            )
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(
            magicjarMode = "MOCK",
            executionBackfillProvider = providerOf(executionBackfillService),
            entryLimitEnabled = true,
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // MOCK 모드도 sync 실행(REAL return 0 결함 정정) + account type=MOCK 전달.
        verify(exactly = 1) { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.MOCK) }
        // REAL account type으로는 호출되지 않는다(모드 일관성).
        verify(exactly = 0) { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.REAL) }
        // sync 통과 → BLOCKED 없이 정상 주문 발행.
        verify(exactly = 1) { signalRiskStatusUpdater.markAllowed(77L) }
        verify(exactly = 0) {
            signalRiskStatusUpdater.markBlocked(any(), RiskRule.KIS_EXECUTION_SYNC_STALE, any())
        }
    }

    @Test
    fun `MOCK 모드 — KIS 체결 sync 불완전 시 BLOCKED 처리한다 (REAL과 동일 fail-closed)`() {
        // MOCK 모드도 sync 불완전(failed > 0)이면 REAL과 똑같이 fail-closed로 주문 차단해야 한다.
        every { executionBackfillService.syncUserTodayExecutions("user-A", AccountType.MOCK) } returns
            KisExecutionSyncResult.failure(
                userId = "user-A",
                accountType = AccountType.MOCK,
                error = "KIS_FETCH_FAILED:timeout",
            )
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }
        // 공짜 룰 선차단(hotfix #5) 통과 위해 KillSwitch off + config/positions/freq stub.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(
            magicjarMode = "MOCK",
            executionBackfillProvider = providerOf(executionBackfillService),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.KIS_EXECUTION_SYNC_STALE, match { it.contains("KIS_EXECUTION_SYNC_STALE") })
        }
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
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } throws IllegalStateException("DB down")
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }
        // 공짜 룰 선차단(hotfix #5) 통과 — KillSwitch off + freq 0(sync 후 buildContext PnL 실패까지 도달).
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(
            magicjarMode = "REAL",
            executionBackfillProvider = providerOf(executionBackfillService),
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.KIS_EXECUTION_SYNC_STALE, match { it.contains("DAILY_PNL_UNAVAILABLE") })
        }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // canary D2 hotfix #2 — trade-frequency record 는 주문 intent 발행 성공 직후에만
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hotfix #2 — BUY 진입 스킵(주문 0) 시 trade-frequency record 헛소모 0`() {
        // 결함 재현: 과거 ALLOWED 시점에 무조건 record → BUY Skip(호가 미가용 등)으로 발행 0건이어도 한도 차감.
        // 정정: 발행되지 않으면 record 호출 0(canary D2 — 거래 0인데 한도 5건 도달 차단 폭주 방지).
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // entryLimitEnabled=true + 호가 null → marketable-limit 산정 불가 → Skip(주문 0).
        listener(entryLimitEnabled = true, entryQuote = null)
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        // 핵심: 발행 0 → record 0(한도 헛소모 차단).
        verify(exactly = 0) { tradeFrequencyGuard.record(any(), any(), any(), any()) }
    }

    @Test
    fun `hotfix #2 — SELL 익절 LIMIT 정합 skip(발행 0) 시 record 0 (오늘 incident 헛소모)`() {
        // 오늘 incident 회귀: 동일가·동일수량 auto-tp 가 이미 있어 발행 skip 되는데도 record 가 돌아 한도를 소모했다.
        sellContext()
        val exitCtx = objectMapper.writeValueAsString(mapOf("exitPriceType" to "LIMIT", "exitLimitPrice" to "73500"))
        listener(openOrders = listOf(restingAutoTp(quantity = 7, limitPrice = "73500")))
            .onSignalGenerated(record(payload(side = "SELL", price = "72000", contextJson = exitCtx)), ack())

        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 0) { tradeFrequencyGuard.record(any(), any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // 거래빈도 = 왕복(신규 포지션 오픈) 단위 record (사용자 결재 2026-06-16)
    //   신규 오픈 BUY(flat→보유)만 1카운트. 스케일인(이미 보유 중 BUY)·모든 SELL(청산)은 record 0.
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `record — 신규 오픈 BUY(flat→보유 전환) 발행 시 record 1회`() {
        // 보유 0(flat)에서 BUY 진입 → 왕복 개시 → record 1.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(entryLimitEnabled = true).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 20, OrderPriceType.LIMIT, BigDecimal("50000")) }
        verify(exactly = 1) { tradeFrequencyGuard.record(100L, "user-A", 77L, now) }
    }

    @Test
    fun `record — 스케일인 BUY(이미 보유 중) 발행돼도 record 0 (왕복 개시 아님)`() {
        // 이미 005930 5주 보유 중 BUY → 추가매수(스케일인) → 신규 오픈 아님 → record 0.
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 5, avgPrice = BigDecimal("49000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(entryLimitEnabled = true).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        // 발행은 정상(스케일인 허용 — 빈도만 미카운트). record 만 0.
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), OrderPriceType.LIMIT, BigDecimal("50000")) }
        verify(exactly = 0) { tradeFrequencyGuard.record(any(), any(), any(), any()) }
    }

    @Test
    fun `record — SELL(청산) 발행돼도 record 0 (청산은 빈도 미카운트)`() {
        sellContext()
        listener().onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
        verify(exactly = 0) { tradeFrequencyGuard.record(any(), any(), any(), any()) }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // canary D2 hotfix #5 — 공짜 룰 선차단이 KIS 원장 TR 호출보다 먼저(자가 rate limit 압박 회피)
    // ─────────────────────────────────────────────────────────────────────────

    @Test
    fun `hotfix #5 — 거래빈도 일 한도 차단 시 KIS 체결동기화 TR 호출 0(원장 절약)`() {
        // 어차피 차단될 시그널은 KIS 체결동기화·잔고 TR 을 소모하지 않는다(어제 EGW00201 자가 압박 차단).
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig(maxTradesPerDay = 5)
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 5
        every { killSwitchQueryPort.isOn("user-A") } returns false
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(executionBackfillProvider = providerOf(executionBackfillService))
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) {
            signalRiskStatusUpdater.markBlocked(77L, RiskRule.TRADE_FREQUENCY_DAY, any())
        }
        // 핵심: 차단 시 KIS 체결동기화·잔고 호출 0 + capital 조회 0(원장 TR 절약).
        verify(exactly = 0) { executionBackfillService.syncUserTodayExecutions(any(), any()) }
        verify(exactly = 0) { capitalSnapshotProvider.capitalOutcome(any()) }
        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any()) }
    }

    @Test
    fun `hotfix #5 — KillSwitch ON 시 positions·KIS 호출조차 생략하고 즉시 차단`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns true
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        listener(executionBackfillProvider = providerOf(executionBackfillService))
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { signalRiskStatusUpdater.markBlocked(77L, RiskRule.KILL_SWITCH_ON, any()) }
        verify(exactly = 0) { executionBackfillService.syncUserTodayExecutions(any(), any()) }
        verify(exactly = 0) { capitalSnapshotProvider.capitalOutcome(any()) }
    }

    @Test
    fun `hotfix #5 보너스 — flat SELL_WITHOUT_POSITION 연속 차단은 STOMP 알람 1회만(쿨다운)`() {
        // 동일 (user·strategy·code·rule) SELL_WITHOUT_POSITION 매분 재발 시 STOMP 알람은 쿨다운 1회만(플러딩 완화).
        // risk_event DB·Kafka 는 매회 유지(감사 누락 0).
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList() // 미보유 → SELL_WITHOUT_POSITION
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false
        every { riskEventRepository.save(any()) } answers { firstArg<RiskEvent>() }

        val l = listener(kisBalance = sampleKisBalance(quantity = 0))
        // 같은 listener 인스턴스로 동일 차단 3회 연속.
        repeat(3) { l.onSignalGenerated(record(payload(side = "SELL", price = "72000")), ack()) }

        // risk_event 는 3회 모두 저장(감사 유지).
        verify(exactly = 3) { riskEventRepository.save(any()) }
        // STOMP 알람(notifyViolation)은 쿨다운으로 1회만.
        verify(exactly = 1) { riskNotifier.notifyViolation(any(), any()) }
    }

    // ── ADR 0129 레버 1·2 — 개장 blackout · late-entry 게이트 + owner 격리 ───────────────────────────────

    /** NotApplicable Guard mock(enabled=true) — owner=AUTOPILOT BUY 가 게이트 통과 시 정상 발행되도록. */
    private fun notApplicableGuard(): kr.spdaewoon.magicjar.consumer.autopilot.AutopilotBuyGuard =
        mockk {
            every { enabled } returns true
            every { evaluate(any(), any(), any(), any()) } returns
                kr.spdaewoon.magicjar.consumer.autopilot.AutopilotBuyDecision.NotApplicable
        }

    /** owner=AUTOPILOT BUY 게이트 시나리오 공통 stub — clock 의 시각으로 risk 통과(ALLOWED). */
    private fun autopilotBuyContext(
        gateClock: Clock,
        allowOvernight: Boolean = false,
    ) {
        every { riskConfigRepository.getOrDefault(any(), any()) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl(any(), any()) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.countRecentDay(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.record(any(), any(), any(), any()) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false
        // late-entry 게이트는 allow_overnight=false 전략만 차단 — lookupStrategyForGuardOrNull(findSellEval) 경로 stub.
        every { strategyLookupPort.findSellEvalByCodeAndInterval(any(), any()) } returns
            listOf(autopilotStrategy(allowOvernight = allowOvernight))
    }

    @Test
    fun `레버2 — 개장 blackout(09_10 KST) owner=AUTOPILOT BUY 차단·intent 미발행`() {
        val gateClock = clockAtKst(9, 10)
        autopilotBuyContext(gateClock)

        listener(entryLimitEnabled = true, listenerClock = gateClock)
            .onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 1) {
            riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_OPEN_BLACKOUT })
        }
    }

    @Test
    fun `레버2 — 개장 윈도우 종료 후(09_16 KST) owner=AUTOPILOT BUY 통과·발행`() {
        val gateClock = clockAtKst(9, 16)
        autopilotBuyContext(gateClock)

        listener(entryLimitEnabled = true, listenerClock = gateClock, autopilotGuard = notApplicableGuard())
            .onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_OPEN_BLACKOUT }) }
    }

    @Test
    fun `레버2 — blackout enabled=false면 윈도우 안(09_10)이어도 통과(롤백)`() {
        val gateClock = clockAtKst(9, 10)
        autopilotBuyContext(gateClock)

        listener(entryLimitEnabled = true, listenerClock = gateClock, openBlackoutEnabled = false, autopilotGuard = notApplicableGuard())
            .onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_OPEN_BLACKOUT }) }
    }

    @Test
    fun `레버2 격리 — USER BUY 는 개장 윈도우(09_10) 안이어도 무영향(통과)`() {
        val gateClock = clockAtKst(9, 10)
        every { riskConfigRepository.getOrDefault(any(), any()) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl(any(), any()) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.countRecentDay(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.record(any(), any(), any(), any()) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // strategyOwner 미지정(=USER 취급) payload → blackout/late-entry 게이트 비적용.
        listener(entryLimitEnabled = true, listenerClock = gateClock)
            .onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_OPEN_BLACKOUT }) }
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED }) }
    }

    @Test
    fun `레버1 — late-entry(14_31 KST) allow_overnight=false owner=AUTOPILOT BUY 차단`() {
        val gateClock = clockAtKst(14, 31)
        autopilotBuyContext(gateClock, allowOvernight = false)

        listener(entryLimitEnabled = true, listenerClock = gateClock)
            .onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        verify(exactly = 0) { orderIntentKafkaProducer.publish(any(), any(), any(), any()) }
        verify(exactly = 1) {
            riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED })
        }
    }

    @Test
    fun `레버1 — late-entry(14_31) allow_overnight=true 전략은 무영향(통과)`() {
        val gateClock = clockAtKst(14, 31)
        autopilotBuyContext(gateClock, allowOvernight = true)

        listener(entryLimitEnabled = true, listenerClock = gateClock, autopilotGuard = notApplicableGuard())
            .onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED }) }
    }

    @Test
    fun `레버1 — cutoff 이전(14_00)에는 allow_overnight=false라도 통과`() {
        val gateClock = clockAtKst(14, 0)
        autopilotBuyContext(gateClock, allowOvernight = false)

        listener(entryLimitEnabled = true, listenerClock = gateClock, autopilotGuard = notApplicableGuard())
            .onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED }) }
    }

    @Test
    fun `레버1 격리 — late-entry(14_31) owner=AUTOPILOT 라도 SELL 은 무영향(청산 보장)`() {
        val gateClock = clockAtKst(14, 31)
        every { riskConfigRepository.getOrDefault(any(), any()) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = 7, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl(any(), any()) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.countRecentDay(any(), any(), any()) } returns 0
        every { killSwitchQueryPort.isOn("user-A") } returns false

        listener(listenerClock = gateClock)
            .onSignalGenerated(record(autopilotPayload(side = "SELL", price = "72000")), ack())

        // SELL 은 게이트 진입부에서 제외 — late-entry 차단 risk_event 없음(청산 항상 보장).
        verify(exactly = 0) { riskEventRepository.save(match { it.rule == RiskRule.AUTOPILOT_LATE_ENTRY_BLOCKED }) }
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), eq(OrderPriceType.MARKET), isNull()) }
    }

    // ── ADR 0130 L2 R1 — Planned Entry plan 박제 ─────────────────────────────────────────

    @Test
    fun `ADR0130 — 게이트 on + owner=AUTOPILOT BUY intent 발행 성공 시 trade_plan 박제`() {
        val gateClock = clockAtKst(11, 0) // blackout·late-entry 비적용 시각.
        autopilotBuyContext(gateClock)
        val captured = slot<kr.spdaewoon.magicjar.domain.position.StrategyPosition>()
        every { strategyPositionPort.upsert(capture(captured)) } answers { captured.captured }

        listener(
            entryLimitEnabled = true,
            listenerClock = gateClock,
            autopilotGuard = notApplicableGuard(),
            strategies = listOf(autopilotStrategy(allowOvernight = true)),
            plannedEntryEnabled = true,
        ).onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        // intent 발행 + plan 박제(trade_plan write) 동반.
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        val plan = captured.captured.tradePlan
        org.junit.jupiter.api.Assertions
            .assertNotNull(plan)
        org.junit.jupiter.api.Assertions
            .assertEquals(100L, plan!!.entry.strategyId)
        org.junit.jupiter.api.Assertions
            .assertTrue(plan.entry.entryPrice.compareTo(BigDecimal("50000")) == 0)
        // 전략 설정(TP 10%·SL 5%)이 plan 에 반영.
        org.junit.jupiter.api.Assertions
            .assertTrue(plan.target?.tpPct?.compareTo(BigDecimal("10.00")) == 0)
        org.junit.jupiter.api.Assertions
            .assertTrue(plan.stop?.slPct?.compareTo(BigDecimal("5.00")) == 0)
        org.junit.jupiter.api.Assertions
            .assertEquals(kr.spdaewoon.magicjar.domain.strategy.TradePlanStatus.OPEN, plan.status)
    }

    @Test
    fun `ADR0130 — 게이트 off 면 owner=AUTOPILOT BUY 라도 trade_plan 박제 skip(zero-regression)`() {
        val gateClock = clockAtKst(11, 0)
        autopilotBuyContext(gateClock)

        listener(
            entryLimitEnabled = true,
            listenerClock = gateClock,
            autopilotGuard = notApplicableGuard(),
            strategies = listOf(autopilotStrategy(allowOvernight = true)),
            plannedEntryEnabled = false,
        ).onSignalGenerated(record(autopilotPayload(side = "BUY", price = "50000")), ack())

        // intent 는 발행되되 plan 박제는 0 (게이트 off = 현행 byte-동일).
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        verify(exactly = 0) {
            strategyPositionPort.upsert(match { it.tradePlan != null })
        }
    }

    @Test
    fun `ADR0130 — 게이트 on 이어도 owner=USER BUY 는 trade_plan 미기록(owner 격리)`() {
        val gateClock = clockAtKst(9, 10)
        every { riskConfigRepository.getOrDefault(any(), any()) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns emptyList()
        every { dailyPnlCalculator.getDailyPnl(any(), any()) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.countRecentDay(any(), any(), any()) } returns 0
        every { tradeFrequencyGuard.record(any(), any(), any(), any()) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false

        // strategyOwner 미운반(=USER 취급) payload + USER 전략(sampleStrategy default owner).
        listener(
            entryLimitEnabled = true,
            listenerClock = gateClock,
            plannedEntryEnabled = true,
        ).onSignalGenerated(record(payload(side = "BUY", price = "50000")), ack())

        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, any(), any(), any()) }
        // USER owner → plan 박제 0 (zero-regression).
        verify(exactly = 0) {
            strategyPositionPort.upsert(match { it.tradePlan != null })
        }
    }

    private fun listener(
        magicjarMode: String = "MOCK",
        // 자본 정합 hotfix(2026-06-08) — 체결 sync는 REAL·MOCK 모두 실행되므로 default로 PASS sync 제공
        // (production에서 KisExecutionBackfillService는 @Component로 항상 합류 — 과거 REAL-only 게이트 때만
        //  MOCK 테스트가 null provider로도 무사했다).
        executionBackfillProvider: ObjectProvider<KisExecutionBackfillService> = passingSyncProvider(magicjarMode),
        killSwitchProvider: ObjectProvider<KillSwitchQueryPort> = providerOf(killSwitchQueryPort),
        strategies: List<Strategy> = listOf(sampleStrategy()),
        kisBalance: KisAccountBalance? = sampleKisBalance(quantity = 100),
        openOrders: List<kr.spdaewoon.magicjar.domain.order.execution.OrderRequest> = emptyList(),
        entryQuote: EntryQuote? =
            EntryQuote(
                ask1 = BigDecimal("50000"),
                bid1 = BigDecimal("49950"),
                askQty1 = 100L,
                asOf = now,
            ),
        // opt-in 게이트(hotfix 2026-06-08) — true면 entry_policy 없는 전략도 marketable-limit. default false=MARKET.
        entryLimitEnabled: Boolean = false,
        // 2026-06-10 incident: consumer ApplicationContext에 RiskConfigRepository 빈 부재(null) 재현용.
        riskConfigProvider: ObjectProvider<RiskConfigRepository> = providerOf(riskConfigRepository),
        // ADR 0129 — autopilot 하드닝 게이트 테스트용 wiring(default=기존 거동 보존).
        listenerClock: Clock = clock,
        openBlackoutEnabled: Boolean = true,
        openBlackoutStart: String = "09:00",
        openBlackoutEnd: String = "09:15",
        eodLiquidationEnabled: Boolean = true,
        lateEntryCutoff: String = "14:30",
        // ADR 0129 — owner=AUTOPILOT BUY 가 게이트 통과 시 발행되도록 NotApplicable Guard 를 wiring(없으면 fail-closed).
        //   USER/SYSTEM owner 는 guard 도달 전 Proceed 라 본 wiring 이 기존 테스트 거동에 영향 0.
        autopilotGuard: kr.spdaewoon.magicjar.consumer.autopilot.AutopilotBuyGuard? = null,
        // ADR 0130 L2 — 계획 기반 진입(Planned Entry) 게이트. default false=박제 skip(현행 byte-동일·zero-regression).
        plannedEntryEnabled: Boolean = false,
    ): SignalGeneratedKafkaListener {
        every { strategyLookupPort.findActiveByCodeAndInterval(any(), any()) } returns strategies
        every { orderRequestRepository.findOpenByUserId(any()) } returns openOrders
        if (kisBalance != null) {
            every { kisBalanceQueryPort.fetchFullBalance(any(), any()) } returns kisBalance
        }
        every { entryQuotePort.latestQuote(any()) } returns entryQuote
        val entryOrderPriceResolver =
            EntryOrderPriceResolver(
                entryQuotePortProvider = providerOf(entryQuotePort),
                limitEnabled = entryLimitEnabled,
                clock = clock,
            )
        return SignalGeneratedKafkaListener(
            objectMapper = objectMapper,
            riskGatewayEvaluator = RiskGatewayEvaluator(),
            tradeFrequencyGuard = tradeFrequencyGuard,
            dailyPnlCalculator = dailyPnlCalculator,
            capitalSnapshotProvider = capitalSnapshotProvider,
            // ADR 0122 — 본 테스트는 USER_DSL(비페이퍼) 시나리오라 페이퍼 provider 부재(isPaper=false → 실 provider 경로 byte 동일).
            paperCapitalSnapshotProviderProvider = nullableProvider(),
            orderIntentKafkaProducer = orderIntentKafkaProducer,
            riskViolationKafkaProducer = riskViolationKafkaProducer,
            autoPauseGuard = autoPauseGuard,
            riskConfigRepositoryProvider = riskConfigProvider,
            riskEventRepositoryProvider = providerOf(riskEventRepository),
            riskPositionPortProvider = providerOf(riskPositionPort),
            strategyPositionPortProvider = providerOf(strategyPositionPort),
            signalRiskStatusUpdaterProvider = providerOf(signalRiskStatusUpdater),
            riskNotifierProvider = providerOf(riskNotifier),
            killSwitchQueryPortProvider = killSwitchProvider,
            orderRequestRepositoryProvider = providerOf(orderRequestRepository),
            strategyLookupPortProvider = providerOf(strategyLookupPort),
            kisBalanceQueryPortProvider = providerOf(kisBalanceQueryPort),
            executionBackfillServiceProvider = executionBackfillProvider,
            entryOrderPriceResolver = entryOrderPriceResolver,
            restingSellOrderCanceller = restingSellOrderCanceller,
            systemEventLoggerProvider = providerOf(systemEventLogger),
            autopilotBuyGuardProvider = autopilotGuard?.let { providerOf(it) } ?: nullableProvider(),
            magicjarMode = magicjarMode,
            openBlackoutEnabled = openBlackoutEnabled,
            openBlackoutStartRaw = openBlackoutStart,
            openBlackoutEndRaw = openBlackoutEnd,
            eodLiquidationEnabled = eodLiquidationEnabled,
            lateEntryCutoffRaw = lateEntryCutoff,
            plannedEntryEnabled = plannedEntryEnabled,
            clock = listenerClock,
        )
    }

    /** ADR 0129 게이트 테스트용 — 지정한 KST 시각의 kotlinx Clock(2026-06-23 평일). */
    private fun clockAtKst(
        hour: Int,
        minute: Int,
    ): Clock {
        val instant =
            java.time.LocalDateTime
                .of(2026, 6, 23, hour, minute)
                .atZone(java.time.ZoneId.of("Asia/Seoul"))
                .toInstant()
        val fixed = Instant.fromEpochMilliseconds(instant.toEpochMilli())
        return object : Clock {
            override fun now(): Instant = fixed
        }
    }

    /** SELL 경로 공통 stub — 보유 [positionQty]주(기본 7) + ALLOWED 통과(resting SELL 시나리오 전용). */
    private fun sellContext(positionQty: Int = 7) {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.capitalOutcome("user-A") } returns CapitalOutcome.Available(BigDecimal("10000000"))
        every { riskPositionPort.findCurrentByUser("user-A") } returns
            listOf(Position(userId = "user-A", code = "005930", quantity = positionQty, avgPrice = BigDecimal("70000"), updatedAt = now))
        every { dailyPnlCalculator.getDailyPnl("user-A", now) } returns BigDecimal.ZERO
        every { tradeFrequencyGuard.countRecentMinute(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.countRecentDay(100L, "user-A", now) } returns 0
        every { tradeFrequencyGuard.record(100L, "user-A", 77L, now) } just Runs
        every { killSwitchQueryPort.isOn("user-A") } returns false
    }

    /** resting `auto-tp:` open SELL(같은 종목·전략) — 보유 수량을 잠그는 익절 LIMIT 선주문. */
    private fun restingAutoTp(
        source: kr.spdaewoon.magicjar.domain.order.execution.OrderSource =
            kr.spdaewoon.magicjar.domain.order.execution.OrderSource.AUTO,
        ackPresent: Boolean = true,
        quantity: Int = 7,
        limitPrice: String = "73500",
        strategyId: Long = 100L,
    ): kr.spdaewoon.magicjar.domain.order.execution.OrderRequest =
        kr.spdaewoon.magicjar.domain.order.execution.OrderRequest(
            id = 9L,
            userId = "user-A",
            signalId = 0L,
            strategyId = strategyId,
            code = "005930",
            side = kr.spdaewoon.magicjar.domain.order.execution.OrderSide.SELL,
            priceType = OrderPriceType.LIMIT,
            quantity = quantity,
            limitPrice = BigDecimal(limitPrice),
            status = kr.spdaewoon.magicjar.domain.order.execution.OrderStatus.ACK,
            mode = kr.spdaewoon.magicjar.domain.order.execution.TradingMode.MOCK,
            kisOrderNo = if (ackPresent) "0001234567" else null,
            kisFwdgOrgno = if (ackPresent) "00950" else null,
            idempotencyKey = "auto-tp:xyz",
            requestedAt = java.time.Instant.parse("2026-05-22T01:00:00Z"),
            source = source,
        )

    private fun sampleConfig(
        maxPositionAmount: BigDecimal = BigDecimal("1000000"),
        maxPositionPercent: BigDecimal = BigDecimal("10.00"),
        maxTradesPerDay: Int = 50,
    ): RiskConfig =
        RiskConfig(
            userId = "user-A",
            maxPositionAmount = maxPositionAmount,
            maxPositionPercent = maxPositionPercent,
            dailyLossLimitAmount = null,
            dailyLossLimitPercent = BigDecimal("3.00"),
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = maxTradesPerDay,
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

    /** owner=AUTOPILOT 전략 (ADR 0129 게이트 테스트용) — allow_overnight 토글 + lookup 매칭(id=100). */
    private fun autopilotStrategy(allowOvernight: Boolean): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            owner = kr.spdaewoon.magicjar.domain.strategy.StrategyOwner.AUTOPILOT,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "ap", buyCondition = null),
                    fixedStopLoss = BigDecimal("5.00"),
                    takeProfitPct = BigDecimal("10.00"),
                    allowOvernight = allowOvernight,
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

    /** strategyOwner=AUTOPILOT 를 운반하는 payload — 게이트가 owner=AUTOPILOT BUY 로 확정하도록. */
    private fun autopilotPayload(
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
                put("strategyOwner", "AUTOPILOT")
            },
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
        contextJson: String? = null,
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
                contextJson?.let { put("contextJson", it) }
            },
        )

    private fun record(value: String) = ConsumerRecord(KafkaTopics.Risk.SIGNAL_GENERATED, 0, 0L, "user-A", value)

    private fun ack(): Acknowledgment = mockk(relaxed = true)

    /**
     * 자본 정합 hotfix(2026-06-08) — 체결 sync가 PASS(완료)로 떨어지는 backfill service provider.
     * mode에서 파생한 account type(MOCK→MOCK / REAL→REAL)으로 호출돼도 완료 결과를 반환해
     * sync 가드가 통과하도록 한다(production wiring 모사).
     */
    private fun passingSyncProvider(magicjarMode: String): ObjectProvider<KisExecutionBackfillService> {
        val accountType =
            kr.spdaewoon.magicjar.domain.runtime.RuntimeModeParser
                .accountTypeOrMock(magicjarMode)
        every { executionBackfillService.syncUserTodayExecutions("user-A", accountType) } returns
            KisExecutionSyncResult(
                userId = "user-A",
                accountType = accountType,
                fetched = 0,
                inserted = 0,
                alreadySynced = 0,
                failed = 0,
                error = null,
                syncedAt = java.time.Instant.parse("2026-05-22T01:00:00Z"),
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

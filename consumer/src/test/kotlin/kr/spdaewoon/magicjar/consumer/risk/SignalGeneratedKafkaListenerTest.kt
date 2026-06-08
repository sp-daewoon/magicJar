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
import kr.spdaewoon.magicjar.domain.order.execution.OrderPriceType
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
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
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
    fun `BUY ALLOWED — entry_policy 없는 전략 + limit-enabled false(default) → MARKET 발행 (자본 경로 회귀 정정)`() {
        // 회귀 핵심: 과거엔 entry_policy=null이어도 marketable-limit 적용 → 호가 미가용 종목 NO_QUOTE 스킵 → BUY 0.
        // 정정: entry_policy 없는 전략은 MARKET 발행으로 즉시 진입 복구(NO_QUOTE 스킵 0).
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
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

        // ADR 0114 §3-3·§5 — exit contextJson 부재(손절·일반 SELL) → MARKET(체결 확실성 우선, 하위호환).
        verify(exactly = 1) { orderIntentKafkaProducer.publish(match { it.id == 77L }, 7, OrderPriceType.MARKET, null) }
    }

    @Test
    fun `SELL 익절(contextJson LIMIT) — LIMIT 발행 (ADR 0114 §3-3·§5)`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
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
    fun `SELL 손절(contextJson MARKET) — MARKET 발행`() {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
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
            orderIntentKafkaProducer = orderIntentKafkaProducer,
            riskViolationKafkaProducer = riskViolationKafkaProducer,
            autoPauseGuard = autoPauseGuard,
            riskConfigRepositoryProvider = providerOf(riskConfigRepository),
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
            magicjarMode = magicjarMode,
            clock = clock,
        )
    }

    /** SELL 경로 공통 stub — 보유 [positionQty]주(기본 7) + ALLOWED 통과(resting SELL 시나리오 전용). */
    private fun sellContext(positionQty: Int = 7) {
        every { riskConfigRepository.getOrDefault("user-A", now) } returns sampleConfig()
        every { capitalSnapshotProvider.getCapital("user-A") } returns BigDecimal("10000000")
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

package kr.spdaewoon.magicjar.api.service.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaEntity
import kr.spdaewoon.magicjar.api.persistence.strategy.StrategyJpaRepository
import kr.spdaewoon.magicjar.api.service.backtest.BacktestPersister
import kr.spdaewoon.magicjar.api.service.backtest.BacktestService
import kr.spdaewoon.magicjar.domain.backtest.ActivationFailReason
import kr.spdaewoon.magicjar.domain.backtest.AutopilotActivationVerdict
import kr.spdaewoon.magicjar.domain.backtest.BacktestPerformance
import kr.spdaewoon.magicjar.domain.backtest.BacktestRequest
import kr.spdaewoon.magicjar.domain.backtest.BacktestResult
import kr.spdaewoon.magicjar.domain.backtest.PriceAdjustmentMode
import kr.spdaewoon.magicjar.domain.backtest.SizingPolicy
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal
import java.time.LocalDate
import kotlinx.datetime.LocalDate as KLocalDate

/**
 * [AutopilotActivationGateService] 단위 테스트 — ADR 0133 §3-B (#5 · R2b).
 *
 * 검증: PASS→ACTIVE+run 영속 / FAIL→BUY_BLOCKED / fail-safe(Aborted·예외→보수 FAIL) / codes 0 보수 FAIL /
 *       BUY_BLOCKED→ACTIVE 복구 / 멱등(같은 입력 같은 판정).
 */
class AutopilotActivationGateServiceTest {
    private val snapshotDate = LocalDate.of(2026, 6, 25)

    private fun strategy(
        id: Long = 1L,
        userId: Long? = 7L,
        codes: String? = """["005930","000660"]""",
        status: String = "ACTIVE",
    ) = StrategyJpaEntity(
        id = id,
        userId = userId,
        owner = "AUTOPILOT",
        name = "autopilot-s$id",
        nameKo = null,
        dslEntry = "RSI(14) < 30",
        dslExit = null,
        codes = codes,
        status = status,
    )

    private fun performance(
        totalTrades: Int = 30,
        winRate: BigDecimal = BigDecimal("0.50"),
        avgWin: BigDecimal = BigDecimal("50000"),
        avgLoss: BigDecimal = BigDecimal("30000"),
        maxDrawdown: BigDecimal = BigDecimal("0.05"),
    ) = BacktestPerformance(
        totalReturn = BigDecimal("0.10"),
        cagr = BigDecimal("0.12"),
        maxDrawdown = maxDrawdown,
        sharpeRatio = BigDecimal("1.2"),
        winRate = winRate,
        totalTrades = totalTrades,
        winningTrades = (totalTrades.toBigDecimal() * winRate).toInt(),
        losingTrades = totalTrades - (totalTrades.toBigDecimal() * winRate).toInt(),
        avgWin = avgWin,
        avgLoss = avgLoss,
        profitFactor = BigDecimal("1.67"),
        tradingFrequency = BigDecimal("0.04"),
    )

    private fun success(perf: BacktestPerformance): BacktestResult.Success =
        BacktestResult.Success(
            request =
                BacktestRequest(
                    strategyId = 1L,
                    universe = listOf("005930"),
                    interval = "1m",
                    from = KLocalDate(2026, 5, 26),
                    to = KLocalDate(2026, 6, 25),
                    initialCapital = BigDecimal("10000000"),
                    priceAdjustmentMode = PriceAdjustmentMode.ADJUSTED,
                    sizingPolicy = SizingPolicy.AllCapital,
                ),
            trades = emptyList(),
            positions = emptyList(),
            finalCapital = BigDecimal("11000000"),
            performance = perf,
            executedAt = Instant.parse("2026-06-25T00:00:00Z"),
            durationMs = 120L,
        )

    private fun aborted(reason: String): BacktestResult.Aborted =
        BacktestResult.Aborted(
            request =
                BacktestRequest(
                    strategyId = 1L,
                    universe = listOf("005930"),
                    interval = "1m",
                    from = KLocalDate(2026, 5, 26),
                    to = KLocalDate(2026, 6, 25),
                    initialCapital = BigDecimal("10000000"),
                ),
            partialTrades = emptyList(),
            reason = reason,
            timeoutCount = 0,
            executedAt = Instant.parse("2026-06-25T00:00:00Z"),
            durationMs = 50L,
        )

    private fun service(
        backtestService: BacktestService,
        persister: BacktestPersister = mockk(relaxed = true),
        strategyRepo: StrategyJpaRepository = mockk(relaxed = true),
        enabled: Boolean = true,
        eventLogger: SystemEventLogger = mockk(relaxed = true),
    ): AutopilotActivationGateService {
        val provider = mockk<ObjectProvider<SystemEventLogger>>()
        every { provider.getIfAvailable() } returns eventLogger
        return AutopilotActivationGateService(
            backtestService = backtestService,
            backtestPersister = persister,
            strategyJpaRepository = strategyRepo,
            systemEventLoggerProvider = provider,
            enabled = enabled,
            minTrades = 20,
            minWinRate = BigDecimal("0.40"),
            minExpectancy = BigDecimal.ZERO,
            maxMdd = BigDecimal("0.15"),
            requireConfidenceRaw = "OK",
            lookbackDaysRaw = 30,
            interval = "1m",
            initialCapitalRaw = BigDecimal("10000000"),
        )
    }

    @Test
    fun `PASS — 합격 기준 전부 통과 시 ACTIVE 유지 + backtest_run 영속`() {
        val bt = mockk<BacktestService>()
        val persister = mockk<BacktestPersister>(relaxed = true)
        val repo = mockk<StrategyJpaRepository>(relaxed = true)
        // trades 30 ≥ 20, winRate 0.50 ≥ 0.40, net expectancy = 0.5*50000 - 0.5*30000 = +10000 > 0, mdd 0.05 ≤ 0.15.
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            success(performance())
        val svc = service(bt, persister, repo)

        val result = svc.evaluateStrategy(strategy(status = "ACTIVE"), snapshotDate)

        assertThat(result.verdict).isEqualTo(AutopilotActivationVerdict.PASS)
        verify(exactly = 1) { persister.persist("7", any()) }
        // 이미 ACTIVE 면 status update 호출 안 함(멱등).
        verify(exactly = 0) { repo.updateStatus(any(), any()) }
        verify(exactly = 0) { repo.markBuyBlocked(any(), any()) }
    }

    @Test
    fun `PASS — BUY_BLOCKED 였던 전략은 ACTIVE 로 복구`() {
        val bt = mockk<BacktestService>()
        val repo = mockk<StrategyJpaRepository>(relaxed = true)
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            success(performance())
        val svc = service(bt, strategyRepo = repo)

        val result = svc.evaluateStrategy(strategy(status = "BUY_BLOCKED"), snapshotDate)

        assertThat(result.passed).isTrue()
        verify(exactly = 1) { repo.updateStatus(1L, "ACTIVE") }
    }

    @Test
    fun `FAIL — 표본 부족(거래 19) 시 BUY_BLOCKED + run 영속 안 함`() {
        val bt = mockk<BacktestService>()
        val persister = mockk<BacktestPersister>(relaxed = true)
        val repo = mockk<StrategyJpaRepository>(relaxed = true)
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            success(performance(totalTrades = 19))
        val svc = service(bt, persister, repo)

        val result = svc.evaluateStrategy(strategy(), snapshotDate)

        assertThat(result.verdict).isEqualTo(AutopilotActivationVerdict.FAIL)
        assertThat(result.failReasons).contains(ActivationFailReason.INSUFFICIENT_SAMPLE)
        verify(exactly = 1) { repo.markBuyBlocked(1L, "ACTIVATION_GATE_FAIL") }
        verify(exactly = 0) { persister.persist(any(), any()) }
    }

    @Test
    fun `FAIL — net expectancy ≤ 0(손익비 나쁨) 시 NEGATIVE_EXPECTANCY`() {
        val bt = mockk<BacktestService>()
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            // winRate 0.40, avgWin 10000, avgLoss 30000 → E = 0.4*10000 - 0.6*30000 = -14000 < 0.
            success(performance(winRate = BigDecimal("0.40"), avgWin = BigDecimal("10000"), avgLoss = BigDecimal("30000")))
        val result = service(bt).evaluateStrategy(strategy(), snapshotDate)

        assertThat(result.verdict).isEqualTo(AutopilotActivationVerdict.FAIL)
        assertThat(result.failReasons).contains(ActivationFailReason.NEGATIVE_EXPECTANCY)
    }

    @Test
    fun `FAIL — MDD 초과(0_16 gt 0_15) 시 MDD_EXCEEDED`() {
        val bt = mockk<BacktestService>()
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            success(performance(maxDrawdown = BigDecimal("0.16")))
        val result = service(bt).evaluateStrategy(strategy(), snapshotDate)

        assertThat(result.failReasons).contains(ActivationFailReason.MDD_EXCEEDED)
    }

    @Test
    fun `fail-safe — 백테스트 NO_CANDLE_DATA Aborted 시 보수 FAIL(INSUFFICIENT_DATA) + BUY_BLOCKED`() {
        val bt = mockk<BacktestService>()
        val repo = mockk<StrategyJpaRepository>(relaxed = true)
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            aborted("NO_CANDLE_DATA")
        val result = service(bt, strategyRepo = repo).evaluateStrategy(strategy(), snapshotDate)

        assertThat(result.verdict).isEqualTo(AutopilotActivationVerdict.FAIL)
        assertThat(result.failReasons).containsExactly(ActivationFailReason.INSUFFICIENT_DATA)
        verify(exactly = 1) { repo.markBuyBlocked(1L, "ACTIVATION_GATE_FAIL") }
    }

    @Test
    fun `fail-safe — 백테스트 실행 예외 시 보수 FAIL(BACKTEST_ERROR)`() {
        val bt = mockk<BacktestService>()
        val repo = mockk<StrategyJpaRepository>(relaxed = true)
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } throws
            RuntimeException("KIS down")
        val result = service(bt, strategyRepo = repo).evaluateStrategy(strategy(), snapshotDate)

        assertThat(result.verdict).isEqualTo(AutopilotActivationVerdict.FAIL)
        assertThat(result.failReasons).containsExactly(ActivationFailReason.BACKTEST_ERROR)
        verify(exactly = 1) { repo.markBuyBlocked(1L, "ACTIVATION_GATE_FAIL") }
    }

    @Test
    fun `fail-safe — codes 0(평가 대상 없음) 시 백테스트 호출 없이 보수 FAIL`() {
        val bt = mockk<BacktestService>(relaxed = true)
        val repo = mockk<StrategyJpaRepository>(relaxed = true)
        val result = service(bt, strategyRepo = repo).evaluateStrategy(strategy(codes = null), snapshotDate)

        assertThat(result.verdict).isEqualTo(AutopilotActivationVerdict.FAIL)
        verify(exactly = 0) { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        verify(exactly = 1) { repo.markBuyBlocked(1L, "ACTIVATION_GATE_FAIL") }
    }

    @Test
    fun `멱등 — 같은 입력 두 번 평가해도 같은 판정`() {
        val bt = mockk<BacktestService>()
        every { bt.runBacktest(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any()) } returns
            success(performance())
        val svc = service(bt)

        val first = svc.evaluateStrategy(strategy(), snapshotDate)
        val second = svc.evaluateStrategy(strategy(), snapshotDate)

        assertThat(first.verdict).isEqualTo(second.verdict)
        assertThat(first.verdict).isEqualTo(AutopilotActivationVerdict.PASS)
    }

    @Test
    fun `lookback — from 은 snapshotDate − lookbackDays`() {
        val bt = mockk<BacktestService>()
        val fromSlot = io.mockk.slot<String>()
        every {
            bt.runBacktest(any(), any(), any(), capture(fromSlot), any(), any(), any(), any(), any(), any(), any(), any(), any())
        } returns success(performance())

        service(bt).evaluateStrategy(strategy(), snapshotDate)

        assertThat(fromSlot.captured).isEqualTo("2026-05-26") // 2026-06-25 − 30일.
    }

    @Test
    fun `isEnabled — ENV false 면 false`() {
        val svc = service(mockk(relaxed = true), enabled = false)
        assertThat(svc.isEnabled()).isFalse()
    }
}

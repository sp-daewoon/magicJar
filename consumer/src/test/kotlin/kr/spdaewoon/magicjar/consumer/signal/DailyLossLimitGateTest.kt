package kr.spdaewoon.magicjar.consumer.signal

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.consumer.risk.RiskGatewayEvaluator
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import kr.spdaewoon.magicjar.domain.risk.port.DailyPnlProvider
import kr.spdaewoon.magicjar.domain.risk.port.RiskConfigRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

class DailyLossLimitGateTest {
    private val evaluator = RiskGatewayEvaluator()
    private val configRepo = mockk<RiskConfigRepository>()
    private val dailyPnlProvider = mockk<DailyPnlProvider>()
    private val capitalProvider = mockk<CapitalSnapshotProvider>()
    private val fixedNow = Instant.parse("2026-06-25T02:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = fixedNow
        }

    private fun config(
        percent: BigDecimal? = BigDecimal("3.00"),
        amount: BigDecimal? = null,
    ): RiskConfig =
        RiskConfig(
            userId = "1234",
            maxPositionAmount = BigDecimal("1000000"),
            maxPositionPercent = BigDecimal("10.00"),
            dailyLossLimitAmount = amount,
            dailyLossLimitPercent = percent,
            maxConcurrentPositions = 5,
            maxTradesPerMinute = 3,
            maxTradesPerDay = 50,
            autoPauseThreshold = 10,
            createdAt = fixedNow,
            updatedAt = fixedNow,
        )

    private fun gate(
        enabled: Boolean = true,
        cacheTtlMillis: Long = 10_000,
        configRepoPresent: Boolean = true,
        pnlPresent: Boolean = true,
        capitalPresent: Boolean = true,
    ): DailyLossLimitGate =
        DailyLossLimitGate(
            riskGatewayEvaluator = evaluator,
            riskConfigRepositoryProvider = if (configRepoPresent) providerOf(configRepo) else emptyProvider(),
            dailyPnlProviderProvider = if (pnlPresent) providerOf(dailyPnlProvider) else emptyProvider(),
            capitalSnapshotProviderProvider = if (capitalPresent) providerOf(capitalProvider) else emptyProvider(),
            enabled = enabled,
            cacheTtlMillis = cacheTtlMillis,
            clock = clock,
        )

    @Test
    fun `한도 도달이면 true`() {
        every { configRepo.getOrDefault("1234", fixedNow) } returns config(percent = BigDecimal("3.00"))
        every { dailyPnlProvider.getDailyPnl("1234", fixedNow) } returns BigDecimal("-50000") // -5%
        every { capitalProvider.capitalOutcome("1234") } returns CapitalOutcome.Available(BigDecimal("1000000"))

        assertThat(gate().isBuyBlockedByDailyLoss("1234")).isTrue()
    }

    @Test
    fun `한도 미도달이면 false`() {
        every { configRepo.getOrDefault("1234", fixedNow) } returns config(percent = BigDecimal("3.00"))
        every { dailyPnlProvider.getDailyPnl("1234", fixedNow) } returns BigDecimal("-20000") // -2%
        every { capitalProvider.capitalOutcome("1234") } returns CapitalOutcome.Available(BigDecimal("1000000"))

        assertThat(gate().isBuyBlockedByDailyLoss("1234")).isFalse()
    }

    @Test
    fun `DLL 비활성이면 capital·pnl 조회조차 안 하고 false`() {
        every { configRepo.getOrDefault("1234", fixedNow) } returns config(percent = null, amount = null)

        assertThat(gate().isBuyBlockedByDailyLoss("1234")).isFalse()
        verify(exactly = 0) { dailyPnlProvider.getDailyPnl(any(), any()) }
        verify(exactly = 0) { capitalProvider.capitalOutcome(any()) }
    }

    @Test
    fun `capital 조회 불가면 percent 한도 자연 비활성(0 진행) — false`() {
        every { configRepo.getOrDefault("1234", fixedNow) } returns config(percent = BigDecimal("3.00"))
        every { dailyPnlProvider.getDailyPnl("1234", fixedNow) } returns BigDecimal("-50000")
        every { capitalProvider.capitalOutcome("1234") } returns CapitalOutcome.Unavailable("KIS_RATE_LIMIT")

        assertThat(gate().isBuyBlockedByDailyLoss("1234")).isFalse()
    }

    @Test
    fun `토글 off면 어떤 조회도 없이 false`() {
        assertThat(gate(enabled = false).isBuyBlockedByDailyLoss("1234")).isFalse()
        verify(exactly = 0) { configRepo.getOrDefault(any(), any()) }
    }

    @Test
    fun `포트 부재면 graceful false(skip 안 함)`() {
        assertThat(gate(configRepoPresent = false).isBuyBlockedByDailyLoss("1234")).isFalse()
    }

    @Test
    fun `조회 예외면 graceful false(skip 안 함)`() {
        every { configRepo.getOrDefault("1234", fixedNow) } throws RuntimeException("db down")
        assertThat(gate().isBuyBlockedByDailyLoss("1234")).isFalse()
    }

    @Test
    fun `TTL 내 재호출은 캐시를 써서 재조회하지 않는다`() {
        every { configRepo.getOrDefault("1234", fixedNow) } returns config(percent = BigDecimal("3.00"))
        every { dailyPnlProvider.getDailyPnl("1234", fixedNow) } returns BigDecimal("-50000")
        every { capitalProvider.capitalOutcome("1234") } returns CapitalOutcome.Available(BigDecimal("1000000"))

        val g = gate(cacheTtlMillis = 60_000)
        assertThat(g.isBuyBlockedByDailyLoss("1234")).isTrue()
        assertThat(g.isBuyBlockedByDailyLoss("1234")).isTrue()

        // clock 고정(fixedNow) → TTL 내 → 조회 1회만.
        verify(exactly = 1) { configRepo.getOrDefault("1234", fixedNow) }
        verify(exactly = 1) { dailyPnlProvider.getDailyPnl("1234", fixedNow) }
        verify(exactly = 1) { capitalProvider.capitalOutcome("1234") }
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

package kr.spdaewoon.magicjar.consumer.risk

import io.mockk.every
import io.mockk.mockk
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.EntryQuote
import kr.spdaewoon.magicjar.domain.market.port.EntryQuotePort
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.ComplexityClass
import kr.spdaewoon.magicjar.domain.strategy.EntryPolicy
import kr.spdaewoon.magicjar.domain.strategy.Strategy
import kr.spdaewoon.magicjar.domain.strategy.StrategyComplexity
import kr.spdaewoon.magicjar.domain.strategy.StrategyDefinition
import kr.spdaewoon.magicjar.domain.strategy.dsl.DslStrategyDefinition
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * [EntryOrderPriceResolver] 배선 단위 테스트 — ADR 0114 §3-1 (R2-C2a).
 *
 * 순수 계산은 EntryPricePolicyTest(strategy-engine)가 보장 — 본 테스트는 배선(호가 주입·전역 default·결과 매핑)만 검증한다.
 */
class EntryOrderPriceResolverTest {
    private val entryQuotePort = mockk<EntryQuotePort>()
    private val now = Instant.parse("2026-05-22T01:00:00Z")
    private val clock =
        object : Clock {
            override fun now(): Instant = now
        }

    private fun resolver(quotePortPresent: Boolean = true): EntryOrderPriceResolver =
        EntryOrderPriceResolver(
            entryQuotePortProvider = if (quotePortPresent) providerOf(entryQuotePort) else emptyProvider(),
            clock = clock,
        )

    private fun buySignal(price: String = "50000"): Signal =
        Signal(
            id = 7L,
            strategyId = 100L,
            userId = "user-A",
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = now,
            side = SignalSide.BUY,
            strength = 80,
            reason = "RULE",
            priceAtSignal = BigDecimal(price),
        )

    private fun strategyWith(entryPolicy: EntryPolicy?): Strategy =
        Strategy(
            id = 100L,
            userId = 1L,
            definition =
                StrategyDefinition(
                    dsl = DslStrategyDefinition(name = "t", buyCondition = null),
                    entryPolicy = entryPolicy,
                ),
            complexity = StrategyComplexity(1, 1, 0, 1, ComplexityClass.LOW),
        )

    @Test
    fun `Enter — ask1 상한 이내면 LIMIT(=ask1) 산정`() {
        // priceAtSignal 50000, 전역 slippage 0.2% → cap 50100. ask1 50000 ≤ cap.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49950"), askQty1 = 100L, asOf = now)

        val decision = resolver().resolve(buySignal(), strategyWith(null))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Enter::class.java)
        val enter = decision as EntryOrderDecision.Enter
        assertThat(enter.limitPrice).isEqualByComparingTo("50000")
        assertThat(enter.entryTimeoutSec).isEqualTo(15) // 전역 default
    }

    @Test
    fun `Skip PriceCapExceeded — ask1 상한 초과면 스킵(시장가 0)`() {
        // ask1 50200 > cap 50100 → PriceCapExceeded.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50200"), bid1 = BigDecimal("50150"), askQty1 = 100L, asOf = now)

        val decision = resolver().resolve(buySignal(), strategyWith(null))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Skip::class.java)
        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_ENTRY_PRICE_CAP")
    }

    @Test
    fun `Skip SpreadTooWide — 스프레드 초과면 스킵`() {
        // ask1 50000, bid1 49000 → spread (1000)/49500*100 ≈ 2.02% > 0.3%.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50000"), bid1 = BigDecimal("49000"), askQty1 = 100L, asOf = now)

        val decision = resolver().resolve(buySignal(), strategyWith(null))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_SPREAD")
    }

    @Test
    fun `Skip NoQuote — 호가 미가용(null)이면 스킵`() {
        every { entryQuotePort.latestQuote("005930") } returns null

        val decision = resolver().resolve(buySignal(), strategyWith(null))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_NO_QUOTE")
    }

    @Test
    fun `Skip QuoteStale — quoteAsOf 임계 초과 경과면 스킵(NO_QUOTE 코드)`() {
        // asOf가 now보다 10초 과거 → default 3초 임계 초과 → QuoteStale(auditCode=SIGNAL_SKIPPED_NO_QUOTE).
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(
                ask1 = BigDecimal("50000"),
                bid1 = BigDecimal("49950"),
                askQty1 = 100L,
                asOf = Instant.parse("2026-05-22T00:59:50Z"),
            )

        val decision = resolver().resolve(buySignal(), strategyWith(null))

        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_NO_QUOTE")
    }

    @Test
    fun `EntryQuotePort bean 부재 — NoQuote skip(시장가 fallback 0)`() {
        val decision = resolver(quotePortPresent = false).resolve(buySignal(), strategyWith(null))
        assertThat((decision as EntryOrderDecision.Skip).auditCode).isEqualTo("SIGNAL_SKIPPED_NO_QUOTE")
    }

    @Test
    fun `전략 entryPolicy override — 더 넓은 슬리피지 상한이 적용된다`() {
        // override slippage 1.0% → cap 50500. ask1 50300 > 전역 cap(50100)이지만 override cap 이내 → Enter.
        every { entryQuotePort.latestQuote("005930") } returns
            EntryQuote(ask1 = BigDecimal("50300"), bid1 = BigDecimal("50250"), askQty1 = 100L, asOf = now)

        val override = EntryPolicy(maxEntrySlippagePct = BigDecimal("1.0"), entryTimeoutSec = 30)
        val decision = resolver().resolve(buySignal(), strategyWith(override))

        assertThat(decision).isInstanceOf(EntryOrderDecision.Enter::class.java)
        val enter = decision as EntryOrderDecision.Enter
        assertThat(enter.limitPrice).isEqualByComparingTo("50300")
        assertThat(enter.entryTimeoutSec).isEqualTo(30) // 전략 override
    }

    private fun <T : Any> providerOf(value: T): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns value }

    private fun <T : Any> emptyProvider(): ObjectProvider<T> =
        mockk<ObjectProvider<T>>().also { every { it.getIfAvailable() } returns null }
}

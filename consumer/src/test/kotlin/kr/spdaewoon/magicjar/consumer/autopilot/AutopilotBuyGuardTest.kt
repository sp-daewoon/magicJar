package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.datetime.Clock
import kr.spdaewoon.magicjar.consumer.persistence.StrategyPositionJpaRepository
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.risk.RiskConfig
import kr.spdaewoon.magicjar.domain.risk.RiskContext
import kr.spdaewoon.magicjar.domain.signal.Signal
import kr.spdaewoon.magicjar.domain.signal.SignalSide
import kr.spdaewoon.magicjar.domain.strategy.StrategyOwner
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal

/**
 * [AutopilotBuyGuard] Guard 매수 경로 wiring 단위 테스트 (spec 2026-06-22 §2-0-A · Phase 0 Task 3 · owner-on-signal P0 견고 fix).
 *
 * ## owner 식별 — 신호 운반(lookup 제거)
 * Guard 는 [Signal.strategyOwner] 를 단일 출처로 owner=AUTOPILOT BUY 를 식별한다([AutopilotBuyGuard.isAutopilotBuy] —
 * Strategy 재조회 0). 과거 lookup 경로(신규 BUY·보유 0 에서 null)가 owner 미상→무게이트 우회를 만들던 race 가 사라진다.
 *
 * 검증 매트릭스: 게이트 off→Reject(벨트앤서스펜더) / owner≠AUTOPILOT 무영향 / owner=AUTOPILOT Allow(보수적 clamp) /
 * Reject / KillTriggered→kill switch 발동 / 노출 조회 실패 fail-closed.
 */
class AutopilotBuyGuardTest {
    private val stateStore = mockk<AutopilotStateStore>()
    private val positionRepo = mockk<StrategyPositionJpaRepository>()
    private val killSwitch = mockk<AutopilotKillSwitch>(relaxed = true)

    private fun guard(enabled: Boolean): AutopilotBuyGuard =
        AutopilotBuyGuard(
            properties = MagicjarAutopilotProperties(enabled = enabled),
            stateStore = stateStore,
            strategyPositionRepository = positionRepo,
            killSwitch = killSwitch,
        )

    @Test
    fun `게이트 off + AUTOPILOT BUY 는 Reject - 벨트앤서스펜더 (실주문 0)`() {
        // spec 2026-06-22 §2-B 방어 심층 — enabled=false 인데 AUTOPILOT BUY 가 어떤 경로로든 들어오면
        // NotApplicable(Proceed→무게이트 실주문)이 아니라 Reject 해 실주문을 0 으로 막는다.
        val decision =
            guard(enabled = false)
                .evaluate(autopilotBuySignal(), context(), BigDecimal("1000000"))
        assertThat(decision).isInstanceOf(AutopilotBuyDecision.Reject::class.java)
        assertThat((decision as AutopilotBuyDecision.Reject).reason).contains("autopilot disabled")
        // 차단이 우선 — state·노출 조회조차 하지 않음(즉시 Reject).
        verify(exactly = 0) { stateStore.load() }
    }

    @Test
    fun `게이트 off + AUTOPILOT SELL 은 NotApplicable - 청산 무영향 (보유분 청산 보장)`() {
        // 벨트앤서스펜더는 BUY 만 막는다. SELL(청산)은 enabled 무관 기존 경로 그대로(롱온리 청산 보장).
        val decision =
            guard(enabled = false)
                .evaluate(autopilotBuySignal().copy(side = SignalSide.SELL), context(), BigDecimal("1000000"))
        assertThat(decision).isInstanceOf(AutopilotBuyDecision.NotApplicable::class.java)
        verify(exactly = 0) { stateStore.load() }
    }

    @Test
    fun `게이트 off + 비 AUTOPILOT BUY 는 NotApplicable - 사용자 전략 byte 동일 (zero-regression)`() {
        // enabled=false 라도 owner=USER 는 차단 대상이 아니다 — 기존 경로 그대로.
        val decision =
            guard(enabled = false)
                .evaluate(userBuySignal(), context(), BigDecimal("1000000"))
        assertThat(decision).isInstanceOf(AutopilotBuyDecision.NotApplicable::class.java)
        verify(exactly = 0) { stateStore.load() }
    }

    @Test
    fun `isAutopilotBuy 는 신호 운반 owner+side 만 본다 (lookup 0)`() {
        val g = guard(enabled = false)
        assertThat(g.isAutopilotBuy(autopilotBuySignal())).isTrue()
        assertThat(g.isAutopilotBuy(autopilotBuySignal().copy(side = SignalSide.SELL))).isFalse()
        assertThat(g.isAutopilotBuy(userBuySignal())).isFalse()
        // owner 미상(legacy null) → AUTOPILOT 아님(호출자가 fail-closed 로 처리).
        assertThat(g.isAutopilotBuy(autopilotBuySignal().copy(strategyOwner = null))).isFalse()
    }

    @Test
    fun `owner != AUTOPILOT 신호는 Guard 미적용 (사용자 전략 무영향)`() {
        val decision =
            guard(enabled = true)
                .evaluate(userBuySignal(), context(), BigDecimal("1000000"))
        assertThat(decision).isInstanceOf(AutopilotBuyDecision.NotApplicable::class.java)
        verify(exactly = 0) { stateStore.load() }
    }

    @Test
    fun `SELL 시그널은 Guard 미적용`() {
        val decision =
            guard(enabled = true)
                .evaluate(autopilotBuySignal().copy(side = SignalSide.SELL), context(), BigDecimal("1000000"))
        assertThat(decision).isInstanceOf(AutopilotBuyDecision.NotApplicable::class.java)
    }

    @Test
    fun `owner=AUTOPILOT Allow - 한도 내 진입 승인`() {
        // 순자산 1억 → 풀=5%=500만. 노출 0, 제안 50만 → 건당캡=풀20%=100만 이내 → Allow(50만).
        every { stateStore.load() } returns AutopilotState()
        every { positionRepo.sumAutopilotExposureByUser(any()) } returns BigDecimal.ZERO
        every { positionRepo.countAutopilotHeldCodesByUser(any()) } returns 0

        val decision =
            guard(enabled = true)
                .evaluate(autopilotBuySignal(), context(netWorth = "100000000"), BigDecimal("500000"))

        assertThat(decision).isInstanceOf(AutopilotBuyDecision.Allow::class.java)
        assertThat((decision as AutopilotBuyDecision.Allow).approvedAmount).isEqualByComparingTo("500000")
    }

    @Test
    fun `owner=AUTOPILOT Reject - 동시보유 한도 도달`() {
        every { stateStore.load() } returns AutopilotState(maxPositions = 3)
        every { positionRepo.sumAutopilotExposureByUser(any()) } returns BigDecimal("100000")
        every { positionRepo.countAutopilotHeldCodesByUser(any()) } returns 3 // == max

        val decision =
            guard(enabled = true)
                .evaluate(autopilotBuySignal(), context(netWorth = "100000000"), BigDecimal("100000"))

        assertThat(decision).isInstanceOf(AutopilotBuyDecision.Reject::class.java)
    }

    @Test
    fun `owner=AUTOPILOT KillTriggered - 누적손실 한도 도달시 kill switch 발동`() {
        // 풀=500만, 누적손실 한도=풀50%=250만. cumRealizedLoss 250만 → KillTriggered.
        every { stateStore.load() } returns
            AutopilotState(cumRealizedLoss = BigDecimal("2500000"))
        every { positionRepo.sumAutopilotExposureByUser(any()) } returns BigDecimal.ZERO
        every { positionRepo.countAutopilotHeldCodesByUser(any()) } returns 0

        val decision =
            guard(enabled = true)
                .evaluate(autopilotBuySignal(), context(netWorth = "100000000"), BigDecimal("100000"))

        assertThat(decision).isInstanceOf(AutopilotBuyDecision.Kill::class.java)
        verify(exactly = 1) { killSwitch.trigger(any()) }
    }

    @Test
    fun `노출 조회 실패는 fail-closed Reject`() {
        every { stateStore.load() } returns AutopilotState()
        every { positionRepo.sumAutopilotExposureByUser(any()) } throws RuntimeException("db down")

        val decision =
            guard(enabled = true)
                .evaluate(autopilotBuySignal(), context(netWorth = "100000000"), BigDecimal("100000"))

        assertThat(decision).isInstanceOf(AutopilotBuyDecision.Reject::class.java)
    }

    // ---- fixtures ----

    private fun autopilotBuySignal(): Signal = buySignal(StrategyOwner.AUTOPILOT)

    private fun userBuySignal(): Signal = buySignal(StrategyOwner.USER)

    private fun buySignal(owner: StrategyOwner): Signal =
        Signal(
            id = 1L,
            strategyId = 100L,
            userId = "user-A",
            code = "005930",
            interval = CandleInterval.FIVE_MIN,
            ts = Clock.System.now(),
            side = SignalSide.BUY,
            strength = 80,
            reason = "test",
            priceAtSignal = BigDecimal("70000"),
            // owner-on-signal — Guard 가 lookup 없이 식별하는 단일 출처.
            strategyOwner = owner,
        )

    private fun context(netWorth: String = "100000000"): RiskContext =
        RiskContext(
            userId = "user-A",
            config = RiskConfig.defaultFor("user-A", Clock.System.now()),
            currentCapital = BigDecimal(netWorth),
            orderableCash = BigDecimal(netWorth),
            currentPositions = emptyList(),
            dailyRealizedPnl = BigDecimal.ZERO,
            recentTradesMinute = 0,
            recentTradesDay = 0,
            killSwitchOn = false,
            nowKst = Clock.System.now(),
        )
}

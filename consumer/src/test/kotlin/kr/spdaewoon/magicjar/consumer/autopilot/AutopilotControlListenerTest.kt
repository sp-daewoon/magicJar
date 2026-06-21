package kr.spdaewoon.magicjar.consumer.autopilot

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import kr.spdaewoon.magicjar.domain.messaging.KafkaTopics
import kr.spdaewoon.magicjar.domain.risk.port.CapitalOutcome
import kr.spdaewoon.magicjar.domain.risk.port.CapitalSnapshotProvider
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.math.BigDecimal

/**
 * [AutopilotControlListener] 단위 테스트 (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * 검증:
 * - zero-regression: enabled=false 면 모든 action no-op.
 * - STOP   → killSwitch.trigger.
 * - RESUME → deactivateKill(플로어 미달 시) + resume 저널.
 * - 플로어 초과(FloorBreached) → 거부 + Slack P0.
 * - net worth 미상 → RESUME 거부(fail-closed).
 * - ROLLBACK → liquidate(mock) + pauseAll + deactivate(kill이면).
 */
class AutopilotControlListenerTest {
    private val killSwitch = mockk<AutopilotKillSwitch>(relaxed = true)
    private val stateStore = mockk<AutopilotStateStore>(relaxed = true)
    private val killJournalService = mockk<AutopilotKillJournalService>(relaxed = true)
    private val capitalProvider = mockk<CapitalSnapshotProvider>()
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>(relaxed = true)
    private val liquidator = mockk<AutopilotLiquidator>()
    private val slack = mockk<SlackWebhookSender>(relaxed = true)
    private val eventLogger = mockk<SystemEventLogger>(relaxed = true)
    private val objectMapper = ObjectMapper().registerKotlinModule()

    private fun listener(
        enabled: Boolean = true,
        liquidatorBean: AutopilotLiquidator? = liquidator,
    ): AutopilotControlListener =
        AutopilotControlListener(
            properties = MagicjarAutopilotProperties(enabled = enabled),
            killSwitch = killSwitch,
            stateStore = stateStore,
            killJournalService = killJournalService,
            capitalSnapshotProvider = capitalProvider,
            strategyRepository = strategyRepo,
            liquidatorProvider = providerOf(liquidatorBean),
            slackSenderProvider = providerOf(slack),
            systemEventLoggerProvider = providerOf(eventLogger),
            objectMapper = objectMapper,
        )

    private fun record(json: String): ConsumerRecord<String, String> =
        ConsumerRecord(KafkaTopics.Autopilot.CONTROL, 0, 0L, "autopilot", json)

    @Test
    fun `enabled=false 면 모든 action no-op (zero-regression)`() {
        listener(enabled = false).onControl(record("""{"action":"STOP","reason":"x"}"""))

        verify(exactly = 0) { killSwitch.trigger(any()) }
        verify(exactly = 0) { stateStore.deactivateKill(any(), any(), any()) }
    }

    @Test
    fun `STOP 은 killSwitch trigger`() {
        listener().onControl(record("""{"action":"STOP","reason":"이상 손실 수동 정지"}"""))

        verify(exactly = 1) { killSwitch.trigger("이상 손실 수동 정지") }
    }

    @Test
    fun `RESUME 플로어 미달이면 deactivate + resume 저널`() {
        every { stateStore.load() } returns AutopilotState(killActive = true, poolPct = BigDecimal("5"))
        every { capitalProvider.capitalOutcome(any()) } returns CapitalOutcome.Available(BigDecimal("20000000"))
        every { stateStore.deactivateKill(any(), any(), any()) } returns
            DeactivateOutcome.Deactivated(AutopilotState(killActive = false))

        listener().onControl(record("""{"action":"RESUME","reason":"전략 축소 재개"}"""))

        // poolCeiling = 20,000,000 × 5/100 = 1,000,000 을 store 에 넘긴다.
        verify(exactly = 1) { stateStore.deactivateKill("전략 축소 재개", BigDecimal("1000000.0000"), null) }
        verify(exactly = 1) { killJournalService.recordResume("전략 축소 재개", null) }
    }

    @Test
    fun `RESUME 플로어 초과면 거부 + Slack P0`() {
        every { stateStore.load() } returns AutopilotState(killActive = true, poolPct = BigDecimal("5"))
        every { capitalProvider.capitalOutcome(any()) } returns CapitalOutcome.Available(BigDecimal("20000000"))
        every { stateStore.deactivateKill(any(), any(), any()) } returns
            DeactivateOutcome.FloorBreached(
                AutopilotState(killActive = true, lifetimeRealizedLoss = BigDecimal("1000000")),
                BigDecimal("1000000"),
            )

        listener().onControl(record("""{"action":"RESUME","reason":"재개 시도"}"""))

        verify(exactly = 1) { slack.sendDirect(match { it.contains("하드 플로어") }) }
        verify(exactly = 0) { killJournalService.recordResume(any(), any()) }
    }

    @Test
    fun `RESUME net worth 미상이면 거부 (fail-closed)`() {
        every { stateStore.load() } returns AutopilotState(killActive = true, poolPct = BigDecimal("5"))
        every { capitalProvider.capitalOutcome(any()) } returns CapitalOutcome.Unavailable("rate limit")

        listener().onControl(record("""{"action":"RESUME","reason":"재개 시도"}"""))

        verify(exactly = 0) { stateStore.deactivateKill(any(), any(), any()) }
        verify(exactly = 1) { slack.sendDirect(match { it.contains("순자산 조회 불가") }) }
    }

    @Test
    fun `ROLLBACK 은 liquidate(mock) + pauseAll + deactivate(kill이면)`() {
        every { liquidator.liquidateAll(any(), any()) } returns AutopilotLiquidationOutcome(listOf("005930"), 1)
        every { strategyRepo.pauseAllAutopilotStrategies() } returns 2
        every { stateStore.load() } returns AutopilotState(killActive = true)
        every { stateStore.deactivateKill(any(), any(), any()) } returns
            DeactivateOutcome.Deactivated(AutopilotState(killActive = false))

        listener().onControl(record("""{"action":"ROLLBACK","reason":"Phase 0 종료"}"""))

        verify(exactly = 1) { liquidator.liquidateAll("1", "Phase 0 종료") }
        verify(exactly = 1) { strategyRepo.pauseAllAutopilotStrategies() }
        verify(exactly = 1) { stateStore.deactivateKill("Phase 0 종료 (rollback)", BigDecimal.ZERO, null) }
    }

    @Test
    fun `ROLLBACK 청산 구현 부재면 청산 skip - PAUSE·deactivate 는 수행`() {
        every { strategyRepo.pauseAllAutopilotStrategies() } returns 1
        every { stateStore.load() } returns AutopilotState(killActive = false)

        // liquidator 빈 부재(T5 미배포).
        listener(liquidatorBean = null).onControl(record("""{"action":"ROLLBACK","reason":"청산기 부재 케이스"}"""))

        verify(exactly = 1) { strategyRepo.pauseAllAutopilotStrategies() }
        // kill off 였으므로 deactivate 미호출.
        verify(exactly = 0) { stateStore.deactivateKill(any(), any(), any()) }
    }

    private fun <T : Any> providerOf(bean: T?): ObjectProvider<T> {
        val provider = mockk<ObjectProvider<T>>()
        every { provider.ifAvailable } returns bean
        every { provider.getIfAvailable() } returns bean
        return provider
    }
}

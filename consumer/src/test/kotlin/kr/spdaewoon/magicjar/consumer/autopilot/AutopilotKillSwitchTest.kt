package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.consumer.notification.SlackWebhookSender
import kr.spdaewoon.magicjar.consumer.persistence.ConsumerStrategyJpaRepository
import kr.spdaewoon.magicjar.domain.autopilot.AutopilotState
import kr.spdaewoon.magicjar.domain.event.port.SystemEventLogger
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider

/**
 * [AutopilotKillSwitch] 단위 테스트 (spec 2026-06-22 §2-0-B · Phase 0 Task 3).
 *
 * 검증: KillTriggered/수동 → kill_active 영속 + owner=AUTOPILOT PAUSE + Slack P0 1회 / 이미 ON 이면 멱등 skip(중복 알림 0).
 */
class AutopilotKillSwitchTest {
    private val stateStore = mockk<AutopilotStateStore>(relaxed = true)
    private val strategyRepo = mockk<ConsumerStrategyJpaRepository>()
    private val slackSender = mockk<SlackWebhookSender>(relaxed = true)
    private val systemEventLogger = mockk<SystemEventLogger>(relaxed = true)

    private val slackProvider = providerOf(slackSender)
    private val eventProvider = providerOf(systemEventLogger)

    private fun killSwitch(): AutopilotKillSwitch =
        AutopilotKillSwitch(
            stateStore = stateStore,
            strategyRepository = strategyRepo,
            slackSenderProvider = slackProvider,
            systemEventLoggerProvider = eventProvider,
        )

    @Test
    fun `kill OFF 상태에서 trigger 하면 영속 + owner=AUTOPILOT PAUSE + Slack P0 1회`() {
        every { stateStore.load() } returns AutopilotState(killActive = false)
        every { strategyRepo.pauseAllAutopilotStrategies() } returns 2

        val fired = killSwitch().trigger("누적 손실 한도 도달")

        assertThat(fired).isTrue()
        verify(exactly = 1) { stateStore.activateKill() }
        verify(exactly = 1) { strategyRepo.pauseAllAutopilotStrategies() }
        verify(exactly = 1) { slackSender.sendDirect(any()) }
        verify(exactly = 1) { systemEventLogger.emit(any()) }
    }

    @Test
    fun `이미 kill ON 이면 멱등 skip - 영속·PAUSE·알림 0`() {
        every { stateStore.load() } returns AutopilotState(killActive = true)

        val fired = killSwitch().trigger("수동 stop")

        assertThat(fired).isFalse()
        verify(exactly = 0) { stateStore.activateKill() }
        verify(exactly = 0) { strategyRepo.pauseAllAutopilotStrategies() }
        verify(exactly = 0) { slackSender.sendDirect(any()) }
    }

    private fun <T : Any> providerOf(bean: T): ObjectProvider<T> {
        val provider = mockk<ObjectProvider<T>>()
        every { provider.ifAvailable } returns bean
        every { provider.getIfAvailable() } returns bean
        return provider
    }
}

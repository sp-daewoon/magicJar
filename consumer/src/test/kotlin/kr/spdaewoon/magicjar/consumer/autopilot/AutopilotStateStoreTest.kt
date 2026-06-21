package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import java.util.Optional

/**
 * [AutopilotStateStore] 영속/매핑/일일 reset 단위 테스트 (spec 2026-06-22 §2-0-A·§2-0-B · Phase 0 Task 3).
 *
 * 검증: 행 부재 시 부트 default fallback / 누적(cum 영구·daily 당일) / 날짜 진행 시 daily reset / kill 영속.
 */
class AutopilotStateStoreTest {
    private val repository = mockk<AutopilotStateJpaRepository>()
    private val properties = MagicjarAutopilotProperties()
    private val store = AutopilotStateStore(repository, properties)

    @Test
    fun `행 부재면 부트 default fallback (손실 0·kill off — 안전측)`() {
        every { repository.findById(1L) } returns Optional.empty()

        val state = store.load()

        assertThat(state.killActive).isFalse()
        assertThat(state.cumRealizedLoss).isEqualByComparingTo(BigDecimal.ZERO)
        assertThat(state.poolPct).isEqualByComparingTo(properties.poolPct)
    }

    @Test
    fun `손실 누적 - cum 영구·daily 당일 (같은 날짜)`() {
        val today = LocalDate.now()
        val entity =
            AutopilotStateJpaEntity(
                cumRealizedLoss = BigDecimal("100000"),
                dailyRealizedLoss = BigDecimal("40000"),
                lastResetDate = today,
            )
        every { repository.findById(1L) } returns Optional.of(entity)
        val saved = slot<AutopilotStateJpaEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val state = store.accumulateRealizedLoss(BigDecimal("30000"))

        assertThat(state.cumRealizedLoss).isEqualByComparingTo("130000")
        assertThat(state.dailyRealizedLoss).isEqualByComparingTo("70000")
        assertThat(saved.captured.lastResetDate).isEqualTo(today)
    }

    @Test
    fun `날짜 진행시 daily reset 후 누적 - cum 은 보존`() {
        val yesterday = LocalDate.now().minusDays(1)
        val entity =
            AutopilotStateJpaEntity(
                cumRealizedLoss = BigDecimal("100000"),
                dailyRealizedLoss = BigDecimal("90000"),
                lastResetDate = yesterday,
            )
        every { repository.findById(1L) } returns Optional.of(entity)
        val saved = slot<AutopilotStateJpaEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val state = store.accumulateRealizedLoss(BigDecimal("20000"))

        // daily 는 어제값 버리고 오늘 20000, cum 은 누적 보존.
        assertThat(state.dailyRealizedLoss).isEqualByComparingTo("20000")
        assertThat(state.cumRealizedLoss).isEqualByComparingTo("120000")
        assertThat(saved.captured.lastResetDate).isEqualTo(LocalDate.now())
    }

    @Test
    fun `이익·무손실(0 이하)은 누적 안 함`() {
        every { repository.findById(1L) } returns Optional.of(AutopilotStateJpaEntity())

        store.accumulateRealizedLoss(BigDecimal.ZERO)

        io.mockk.verify(exactly = 0) { repository.save(any()) }
    }

    @Test
    fun `kill 영속 - 행 갱신 kill_active true`() {
        every { repository.findById(1L) } returns Optional.of(AutopilotStateJpaEntity(killActive = false))
        val saved = slot<AutopilotStateJpaEntity>()
        every { repository.save(capture(saved)) } answers { saved.captured }

        val state = store.activateKill()

        assertThat(state.killActive).isTrue()
        assertThat(saved.captured.killActive).isTrue()
    }

    @Test
    fun `kill 이미 ON 이면 멱등 - save 0`() {
        every { repository.findById(1L) } returns Optional.of(AutopilotStateJpaEntity(killActive = true))

        val state = store.activateKill()

        assertThat(state.killActive).isTrue()
        io.mockk.verify(exactly = 0) { repository.save(any()) }
    }
}

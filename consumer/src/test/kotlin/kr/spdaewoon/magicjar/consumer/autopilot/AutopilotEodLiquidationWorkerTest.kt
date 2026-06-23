package kr.spdaewoon.magicjar.consumer.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.session.BusinessDayCalendar
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.ObjectProvider
import java.time.Clock
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

/**
 * [AutopilotEodLiquidationWorker] 단위 테스트 — ADR 0129 레버 1 EOD 청산.
 *
 * ## 검증 매트릭스
 *  - cutoff 이전(14:00) → 청산 미발사.
 *  - cutoff 도달(15:15)·거래일·autopilot enabled → liquidateNoOvernightAutopilot 1회 발사.
 *  - 같은 거래일 2회 tick → 멱등(발사 1회만).
 *  - 휴장일 → 청산 미발사.
 *  - enabled=false(워커 토글) → no-op(롤백).
 *  - autopilot enabled=false → no-op.
 */
class AutopilotEodLiquidationWorkerTest {
    private val kst: ZoneId = ZoneId.of("Asia/Seoul")
    private val liquidator = mockk<AutopilotLiquidator>(relaxed = true)
    private val calendar = mockk<BusinessDayCalendar>()

    private fun providerOf(value: AutopilotLiquidator?): ObjectProvider<AutopilotLiquidator> =
        mockk { every { getIfAvailable() } returns value }

    private fun calendarProvider(value: BusinessDayCalendar?): ObjectProvider<BusinessDayCalendar> =
        mockk { every { getIfAvailable() } returns value }

    private fun propsProvider(autopilotEnabled: Boolean): ObjectProvider<MagicjarAutopilotProperties> =
        mockk { every { getIfAvailable() } returns MagicjarAutopilotProperties(enabled = autopilotEnabled) }

    private fun clockAt(time: LocalTime): Clock {
        val dateTime = LocalDateTime.of(LocalDate.of(2026, 6, 23), time) // 2026-06-23 = 화요일(평일)
        return Clock.fixed(dateTime.atZone(kst).toInstant(), kst)
    }

    private fun worker(
        clock: Clock,
        enabled: Boolean = true,
        autopilotEnabled: Boolean = true,
        cutoff: String = "15:15",
        cal: BusinessDayCalendar? = calendar,
        liq: AutopilotLiquidator? = liquidator,
    ) = AutopilotEodLiquidationWorker(
        autopilotLiquidatorProvider = providerOf(liq),
        businessDayCalendarProvider = calendarProvider(cal),
        autopilotPropertiesProvider = propsProvider(autopilotEnabled),
        enabled = enabled,
        cutoffRaw = cutoff,
        clock = clock,
    )

    @Test
    fun `cutoff 이전(14_00) — 청산 미발사`() {
        val w = worker(clockAt(LocalTime.of(14, 0)))
        w.eodTick()
        verify(exactly = 0) { liquidator.liquidateNoOvernightAutopilot(any(), any()) }
    }

    @Test
    fun `cutoff 도달(15_15) + 거래일 + autopilot enabled — 1회 청산 발사`() {
        every { calendar.isBusinessDay(LocalDate.of(2026, 6, 23)) } returns true
        val w = worker(clockAt(LocalTime.of(15, 15)))

        w.eodTick()

        verify(exactly = 1) { liquidator.liquidateNoOvernightAutopilot(null, "EOD_NO_OVERNIGHT") }
    }

    @Test
    fun `같은 거래일 2회 tick — 멱등(발사 1회만)`() {
        every { calendar.isBusinessDay(any()) } returns true
        val w = worker(clockAt(LocalTime.of(15, 20)))

        w.eodTick()
        w.eodTick()

        verify(exactly = 1) { liquidator.liquidateNoOvernightAutopilot(any(), any()) }
    }

    @Test
    fun `휴장일 — 청산 미발사`() {
        every { calendar.isBusinessDay(any()) } returns false
        val w = worker(clockAt(LocalTime.of(15, 30)))

        w.eodTick()

        verify(exactly = 0) { liquidator.liquidateNoOvernightAutopilot(any(), any()) }
    }

    @Test
    fun `워커 enabled=false — no-op(롤백)`() {
        val w = worker(clockAt(LocalTime.of(15, 30)), enabled = false)
        w.eodTick()
        verify(exactly = 0) { liquidator.liquidateNoOvernightAutopilot(any(), any()) }
    }

    @Test
    fun `autopilot enabled=false — no-op`() {
        val w = worker(clockAt(LocalTime.of(15, 30)), autopilotEnabled = false)
        w.eodTick()
        verify(exactly = 0) { liquidator.liquidateNoOvernightAutopilot(any(), any()) }
    }
}

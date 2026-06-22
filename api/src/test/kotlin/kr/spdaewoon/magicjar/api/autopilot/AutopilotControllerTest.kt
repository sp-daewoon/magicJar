package kr.spdaewoon.magicjar.api.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotCodePnl
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotControlProducer
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotController
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotCorrectedLimits
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotPerformanceResponse
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotPerformanceService
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotResumeRequest
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotRollbackRequest
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotStatusResponse
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotStatusService
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotStopRequest
import kr.spdaewoon.magicjar.domain.order.execution.TradingMode
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal
import java.time.LocalDate
import java.time.OffsetDateTime

/**
 * [AutopilotController] 단위 테스트 (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * 검증: 4 endpoint 응답 / resume reason 빈값 400 / 각 action 이 producer 에 정확히 publish.
 * mockk 로 service·producer 격리(WebMvc 슬라이스 불요 — 컨트롤러 분기 로직 직접 검증).
 */
class AutopilotControllerTest {
    private val statusService = mockk<AutopilotStatusService>()
    private val performanceService = mockk<AutopilotPerformanceService>()
    private val producer = mockk<AutopilotControlProducer>()
    private val controller = AutopilotController(statusService, performanceService, producer)

    @Test
    fun `status는 service 결과를 그대로 반환한다`() {
        val response =
            AutopilotStatusResponse(
                killActive = false,
                poolPct = BigDecimal("5"),
                perTradePct = BigDecimal("20"),
                maxPositions = 3,
                dailyLossPct = BigDecimal("30"),
                cumLossPct = BigDecimal("50"),
                cumRealizedLoss = BigDecimal.ZERO,
                dailyRealizedLoss = BigDecimal.ZERO,
                lifetimeRealizedLoss = BigDecimal.ZERO,
                autopilotStrategyCount = 2,
                realizedPnlToday = BigDecimal("2728.00"),
            )
        every { statusService.status() } returns response

        assertThat(controller.status()).isSameAs(response)
    }

    @Test
    fun `performance는 service 결과를 그대로 반환한다 (mode null이면 REAL 보정)`() {
        val response =
            AutopilotPerformanceResponse(
                period = "DAY",
                periodStart = LocalDate.of(2026, 6, 22),
                periodEnd = LocalDate.of(2026, 6, 22),
                mode = "REAL",
                realizedPnl = BigDecimal("2728.00"),
                tradeCount = 8,
                winCount = 2,
                lossCount = 5,
                winRatePct = BigDecimal("28.57"),
                profitLossRatio = BigDecimal("1.85"),
                scopedStrategyCount = 6,
                byCode = listOf(AutopilotCodePnl("080220", "제주반도체", BigDecimal("8947.00"), 1)),
                kisScopingNote = "owner-scoped",
                computedAt = OffsetDateTime.now(),
            )
        every { performanceService.performance("DAY", TradingMode.REAL) } returns response

        // mode 미지정 → REAL 보정
        assertThat(controller.performance("DAY", null)).isSameAs(response)
        verify(exactly = 1) { performanceService.performance("DAY", TradingMode.REAL) }
    }

    @Test
    fun `stop은 STOP을 publish하고 202를 반환한다`() {
        every { producer.publish("STOP", any(), null) } returns "req-stop"

        val res = controller.stop(AutopilotStopRequest(reason = "이상 손실"))

        assertThat(res.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(res.body?.action).isEqualTo("STOP")
        assertThat(res.body?.requestId).isEqualTo("req-stop")
        verify(exactly = 1) { producer.publish("STOP", "이상 손실", null) }
    }

    @Test
    fun `resume은 RESUME을 publish하고 202를 반환한다 (correctedLimits 전달)`() {
        val limits = AutopilotCorrectedLimits(poolPct = BigDecimal("3"))
        every { producer.publish("RESUME", any(), any()) } returns "req-resume"

        val res = controller.resume(AutopilotResumeRequest(reason = "전략42 과적합 — 풀 축소", correctedLimits = limits))

        assertThat(res.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(res.body?.action).isEqualTo("RESUME")
        verify(exactly = 1) { producer.publish("RESUME", "전략42 과적합 — 풀 축소", limits) }
    }

    @Test
    fun `resume reason 빈값이면 400 - publish 0`() {
        assertThatThrownBy { controller.resume(AutopilotResumeRequest(reason = "   ")) }
            .isInstanceOf(ResponseStatusException::class.java)
            .extracting("statusCode")
            .isEqualTo(HttpStatus.BAD_REQUEST)

        verify(exactly = 0) { producer.publish(any(), any(), any()) }
    }

    @Test
    fun `rollback은 ROLLBACK을 publish하고 202를 반환한다`() {
        every { producer.publish("ROLLBACK", any(), null) } returns "req-rb"

        val res = controller.rollback(AutopilotRollbackRequest(reason = "Phase 0 종료"))

        assertThat(res.statusCode).isEqualTo(HttpStatus.ACCEPTED)
        assertThat(res.body?.action).isEqualTo("ROLLBACK")
        verify(exactly = 1) { producer.publish("ROLLBACK", "Phase 0 종료", null) }
    }
}

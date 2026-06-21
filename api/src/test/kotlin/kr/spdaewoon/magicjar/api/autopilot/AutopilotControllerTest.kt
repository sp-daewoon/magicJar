package kr.spdaewoon.magicjar.api.autopilot

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotControlProducer
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotController
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotCorrectedLimits
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotResumeRequest
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotRollbackRequest
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotStatusResponse
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotStatusService
import kr.spdaewoon.magicjar.api.controller.autopilot.AutopilotStopRequest
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.web.server.ResponseStatusException
import java.math.BigDecimal

/**
 * [AutopilotController] 단위 테스트 (spec 2026-06-22 §2-0-B·§3 · Phase 0 Task 4).
 *
 * 검증: 4 endpoint 응답 / resume reason 빈값 400 / 각 action 이 producer 에 정확히 publish.
 * mockk 로 service·producer 격리(WebMvc 슬라이스 불요 — 컨트롤러 분기 로직 직접 검증).
 */
class AutopilotControllerTest {
    private val statusService = mockk<AutopilotStatusService>()
    private val producer = mockk<AutopilotControlProducer>()
    private val controller = AutopilotController(statusService, producer)

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
            )
        every { statusService.status() } returns response

        assertThat(controller.status()).isSameAs(response)
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

package kr.spdaewoon.magicjar.consumer.aggregation

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * CandleBackfillAdminController 단위 테스트 — 햇징 룰 #10 운영자 우회 path 검증.
 */
class CandleBackfillAdminControllerTest {
    private val worker = mockk<CandleBackfillWorker>()
    private val controller = CandleBackfillAdminController(worker)

    @Test
    fun `룰 #10 운영자 우회 — dry-run true 경로 응답 LOCK (BackfillResponse dryRun true + status SUCCESS)`() {
        every {
            worker.runBackfill(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                dryRun = true,
                windowDays = 30,
                reason = "admin",
            )
        } returns
            CandleBackfillWorker.BackfillResult(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                backfillBatchId = "candle-backfill:004090:1d:1700000000000-uuid",
                dryRun = true,
                gapCount = 5,
                gapsSample = listOf(Instant.ofEpochSecond(1_700_000_000L)),
                insertedCount = 0,
                failedGapsCount = 0,
                durationMs = 12L,
                status = CandleBackfillWorker.BackfillStatus.SUCCESS,
            )

        val response = controller.trigger("004090", "1d", dryRun = true, windowDays = 30)

        assertThat(response.statusCode.is2xxSuccessful).isTrue
        val body = response.body!!
        assertThat(body.dryRun).isTrue
        assertThat(body.status).isEqualTo("SUCCESS")
        assertThat(body.gapCount).isEqualTo(5)
        assertThat(body.insertedCount).isEqualTo(0)
        assertThat(body.backfillBatchId).startsWith("candle-backfill:")
        // worker dryRun=true 위임 LOCK
        verify(exactly = 1) {
            worker.runBackfill(
                code = "004090",
                interval = CandleInterval.ONE_DAY,
                dryRun = true,
                windowDays = 30,
                reason = "admin",
            )
        }
    }

    @Test
    fun `interval 미지원 (5m) — 400 BAD_REQUEST + error message`() {
        val response = controller.trigger("004090", "5m", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.status).isEqualTo("BAD_REQUEST")
        assertThat(response.body!!.error).contains("Phase 2-A")
    }

    @Test
    fun `unknown interval — 400 BAD_REQUEST`() {
        val response = controller.trigger("004090", "unknown", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.error).contains("Unknown interval")
    }

    @Test
    fun `windowDays out of range — 400 BAD_REQUEST`() {
        val response = controller.trigger("004090", "1d", dryRun = false, windowDays = 1000)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.error).contains("windowDays out of range")
    }

    @Test
    fun `code blank — 400 BAD_REQUEST`() {
        val response = controller.trigger("", "1d", dryRun = false, windowDays = 30)

        assertThat(response.statusCode.is4xxClientError).isTrue
        assertThat(response.body!!.error).contains("Invalid code")
    }
}

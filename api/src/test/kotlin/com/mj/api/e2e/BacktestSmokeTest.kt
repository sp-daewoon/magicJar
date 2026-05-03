package com.mj.api.e2e

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.application.backtest.BacktestDetailQueryService
import com.mj.api.application.backtest.BacktestRunStatus
import com.mj.api.application.backtest.BacktestService
import com.mj.api.application.backtest.BacktestStatusResponse
import com.mj.api.application.backtest.BacktestTriggerResponse
import com.mj.api.config.WebSecurityConfig
import com.mj.api.controller.BacktestController
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

/**
 * BacktestSmokeTest — e2e smoke: POST /api/backtest → runId → GET /api/backtest/{runId} → COMPLETED
 *
 * Phase E-P2 정책:
 * - batch-app 없이도 smoke가 PASS가 되어야 함 (stub fixture 기반)
 * - BacktestService를 mock으로 처리: QUEUED → COMPLETED 흐름 시뮬레이션
 * - Testcontainers PG + Kafka 포함 완전 통합 e2e는 E-P3에서 별도 추가
 *
 * 이 테스트가 PASS = smoke.sh S1 PASS 전제 조건 검증
 */
@WebMvcTest(
    controllers = [BacktestController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class BacktestSmokeTest {
    @TestConfiguration
    class SmokeConfig {
        @Bean
        fun backtestService(): BacktestService = mockk()

        @Bean
        fun backtestDetailQueryService(): BacktestDetailQueryService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var backtestService: BacktestService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    /**
     * Smoke S1: POST /api/backtest → 202 + runId → GET /api/backtest/{runId} → COMPLETED
     *
     * 이 시나리오가 smoke.sh scenario_1에 대응한다.
     */
    @Test
    fun `S1 backtest trigger returns runId and status is eventually COMPLETED`() {
        val runId = "smoke-run-001"

        // 1. POST 트리거
        every { backtestService.trigger(any()) } returns
            BacktestTriggerResponse(
                runId = runId,
                status = BacktestRunStatus.QUEUED,
                message = "백테스트 요청이 접수됐습니다.",
            )

        val postResult =
            mvc
                .post("/api/backtest") {
                    contentType = MediaType.APPLICATION_JSON
                    content = """{"strategyId":"sma-cross-5-20","symbol":"005930","from":"2024-01-01","to":"2024-12-31"}"""
                }.andExpect {
                    status { isAccepted() }
                    jsonPath("$.runId") { value(runId) }
                    jsonPath("$.status") { value("QUEUED") }
                }.andReturn()

        val responseBody = postResult.response.contentAsString
        val triggerResp = objectMapper.readValue(responseBody, Map::class.java)
        val returnedRunId = triggerResp["runId"] as String
        assertEquals(runId, returnedRunId, "runId must match")

        // 2. GET 상태 조회 → COMPLETED (batch-app이 처리 완료한 상태 시뮬레이션)
        every { backtestService.getStatus(runId) } returns
            BacktestStatusResponse(
                runId = runId,
                strategyId = "sma-cross-5-20",
                symbol = "005930",
                from = "2024-01-01",
                to = "2024-12-31",
                status = BacktestRunStatus.COMPLETED,
                startedAt = "2026-04-27T00:00:00Z",
                finishedAt = "2026-04-27T00:00:10Z",
                summaryJson = """{"totalReturn":0.127,"tradeCount":15,"journalEntries":1}""",
                errorMessage = null,
            )

        mvc
            .get("/api/backtest/$runId")
            .andExpect {
                status { isOk() }
                jsonPath("$.runId") { value(runId) }
                jsonPath("$.status") { value("COMPLETED") }
                jsonPath("$.summaryJson") { exists() }
            }
    }

    @Test
    fun `S1 backtest trigger body validation - missing strategyId returns 400`() {
        mvc
            .post("/api/backtest") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"symbol":"005930","from":"2024-01-01","to":"2024-12-31"}"""
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `S1 status query for unknown runId returns 404`() {
        every { backtestService.getStatus("unknown-run") } returns null

        mvc
            .get("/api/backtest/unknown-run")
            .andExpect {
                status { isNotFound() }
            }
    }
}

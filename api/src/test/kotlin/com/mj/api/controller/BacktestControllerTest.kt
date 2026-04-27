package com.mj.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.application.backtest.BacktestRunStatus
import com.mj.api.application.backtest.BacktestService
import com.mj.api.application.backtest.BacktestStatusResponse
import com.mj.api.application.backtest.BacktestTriggerResponse
import com.mj.api.config.WebSecurityConfig
import io.mockk.every
import io.mockk.mockk
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
class BacktestControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun backtestService(): BacktestService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var backtestService: BacktestService

    @Autowired
    lateinit var mapper: ObjectMapper

    @Test
    fun `POST api backtest returns 202 with runId`() {
        every { backtestService.trigger(any()) } returns
            BacktestTriggerResponse(
                runId = "test-run-1",
                status = BacktestRunStatus.QUEUED,
                message = "접수",
            )

        mvc
            .post("/api/backtest") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"strategyId":"sma-cross","symbol":"005930","from":"2024-01-01","to":"2024-12-31"}"""
            }.andExpect {
                status { isAccepted() }
                jsonPath("$.runId") { value("test-run-1") }
                jsonPath("$.status") { value("QUEUED") }
            }
    }

    @Test
    fun `GET api backtest runId returns 200 for existing run`() {
        every { backtestService.getStatus("test-run-1") } returns
            BacktestStatusResponse(
                runId = "test-run-1",
                strategyId = "sma-cross",
                symbol = "005930",
                from = "2024-01-01",
                to = "2024-12-31",
                status = BacktestRunStatus.COMPLETED,
                startedAt = null,
                finishedAt = null,
                summaryJson = null,
                errorMessage = null,
            )

        mvc
            .get("/api/backtest/test-run-1")
            .andExpect {
                status { isOk() }
                jsonPath("$.status") { value("COMPLETED") }
            }
    }

    @Test
    fun `GET api backtest runId returns 404 for unknown run`() {
        every { backtestService.getStatus("unknown") } returns null

        mvc
            .get("/api/backtest/unknown")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `POST api backtest with invalid body returns 400`() {
        mvc
            .post("/api/backtest") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"symbol":"005930"}"""
            }.andExpect {
                status { isBadRequest() }
            }
    }
}

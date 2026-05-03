package com.mj.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.application.backtest.BacktestDetailQueryService
import com.mj.api.application.backtest.BacktestRunStatus
import com.mj.api.application.backtest.BacktestService
import com.mj.api.application.backtest.BacktestStatusResponse
import com.mj.api.application.backtest.BacktestTriggerResponse
import com.mj.api.application.backtest.EquityCurveResponse
import com.mj.api.application.backtest.EquityPointDto
import com.mj.api.application.backtest.TradeDto
import com.mj.api.application.backtest.TradesResponse
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

        @Bean
        fun backtestDetailQueryService(): BacktestDetailQueryService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var backtestService: BacktestService

    @Autowired
    lateinit var backtestDetailQueryService: BacktestDetailQueryService

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

    @Test
    fun `GET equity-curve returns 200 with points for existing run`() {
        every { backtestDetailQueryService.getEquityCurve("run-1") } returns
            EquityCurveResponse(
                runId = "run-1",
                count = 2,
                points =
                    listOf(
                        EquityPointDto(
                            sequence = 0,
                            at = "2024-01-01T00:00:00Z",
                            equity = java.math.BigDecimal("1000000.00"),
                            cash = java.math.BigDecimal("1000000.00"),
                            positionValue = java.math.BigDecimal("0.00"),
                        ),
                        EquityPointDto(
                            sequence = 1,
                            at = "2024-01-02T00:00:00Z",
                            equity = java.math.BigDecimal("1050000.00"),
                            cash = java.math.BigDecimal("0.00"),
                            positionValue = java.math.BigDecimal("1050000.00"),
                        ),
                    ),
            )

        mvc
            .get("/api/backtest/run-1/equity-curve")
            .andExpect {
                status { isOk() }
                jsonPath("$.runId") { value("run-1") }
                jsonPath("$.count") { value(2) }
                jsonPath("$.points.length()") { value(2) }
                jsonPath("$.points[0].sequence") { value(0) }
                jsonPath("$.points[1].equity") { value(1050000.00) }
            }
    }

    @Test
    fun `GET equity-curve returns 404 for unknown run`() {
        every { backtestDetailQueryService.getEquityCurve("unknown") } returns null

        mvc
            .get("/api/backtest/unknown/equity-curve")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET trades returns 200 with trade list`() {
        every { backtestDetailQueryService.getTrades("run-1") } returns
            TradesResponse(
                runId = "run-1",
                count = 1,
                trades =
                    listOf(
                        TradeDto(
                            tradeId = "t-1",
                            sequence = 0,
                            symbol = "005930",
                            entryAt = "2024-01-02T00:00:00Z",
                            exitAt = "2024-01-10T00:00:00Z",
                            entryPrice = java.math.BigDecimal("70000.00"),
                            exitPrice = java.math.BigDecimal("73000.00"),
                            shares = 100,
                            pnl = java.math.BigDecimal("298000.00"),
                            pnlPercent = java.math.BigDecimal("0.04257143"),
                            entryCommission = java.math.BigDecimal("105.00"),
                            exitCommission = java.math.BigDecimal("109.50"),
                            exitTax = java.math.BigDecimal("1314.00"),
                            exitReason = "SIGNAL_EXIT",
                        ),
                    ),
            )

        mvc
            .get("/api/backtest/run-1/trades")
            .andExpect {
                status { isOk() }
                jsonPath("$.count") { value(1) }
                jsonPath("$.trades[0].tradeId") { value("t-1") }
                jsonPath("$.trades[0].symbol") { value("005930") }
                jsonPath("$.trades[0].pnl") { value(298000.00) }
                jsonPath("$.trades[0].exitReason") { value("SIGNAL_EXIT") }
            }
    }

    @Test
    fun `GET trades returns 404 for unknown run`() {
        every { backtestDetailQueryService.getTrades("unknown") } returns null

        mvc
            .get("/api/backtest/unknown/trades")
            .andExpect {
                status { isNotFound() }
            }
    }
}

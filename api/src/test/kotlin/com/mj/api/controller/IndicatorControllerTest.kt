package com.mj.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.common.Candle
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.IndicatorPoint
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.indicator.catalog.IndicatorCatalog
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
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
import java.math.BigDecimal
import java.time.Instant

@WebMvcTest(
    controllers = [IndicatorController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class IndicatorControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun indicatorEngine(): IndicatorEngine = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var engine: IndicatorEngine

    @Autowired
    lateinit var objectMapper: ObjectMapper

    // ─────────────────────────────────────────────
    // GET /api/indicators
    // ─────────────────────────────────────────────

    @Test
    fun `GET catalog — 200 with 80 entries`() {
        every { engine.allDescriptors() } returns IndicatorCatalog.ALL

        mvc.get("/api/indicators").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(80) }
        }
    }

    @Test
    fun `GET catalog category=MOMENTUM — filtered`() {
        every { engine.allDescriptors() } returns IndicatorCatalog.ALL

        mvc.get("/api/indicators?category=MOMENTUM").andExpect {
            status { isOk() }
            // MOMENTUM 카테고리 20개
            jsonPath("$.length()") { value(20) }
        }
    }

    @Test
    fun `GET catalog invalid category — 400`() {
        every { engine.allDescriptors() } returns IndicatorCatalog.ALL

        mvc.get("/api/indicators?category=INVALID_CAT").andExpect {
            status { isBadRequest() }
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/indicators/compute
    // ─────────────────────────────────────────────

    @Test
    fun `POST compute RSI — 200 with series`() {
        val spec = IndicatorSpec("rsi", mapOf("period" to 14))
        val mockResult =
            IndicatorResult(
                spec = spec,
                series =
                    listOf(
                        IndicatorPoint(Instant.parse("2026-01-02T00:00:00Z"), mapOf("rsi" to BigDecimal("65.40"))),
                    ),
                source = "native",
            )
        every { engine.calculate(any(), any<List<Candle>>()) } returns mockResult

        val body =
            """
            {
              "spec": {"name": "rsi", "params": {"period": 14}},
              "candles": [
                {"time": "2026-01-01T00:00:00Z", "open": 60000, "high": 61000, "low": 59000, "close": 60500, "volume": 1000000},
                {"time": "2026-01-02T00:00:00Z", "open": 60500, "high": 62000, "low": 60000, "close": 61500, "volume": 1200000}
              ]
            }
            """.trimIndent()

        mvc
            .post("/api/indicators/compute") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.spec.name") { value("rsi") }
                jsonPath("$.source") { value("native") }
                jsonPath("$.series.length()") { value(1) }
                jsonPath("$.pointCount") { value(1) }
            }
    }

    @Test
    fun `POST compute empty candles — 400`() {
        val body = """{"spec": {"name": "rsi"}, "candles": []}"""

        mvc
            .post("/api/indicators/compute") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST compute unsupported indicator — 422`() {
        every { engine.calculate(any(), any<List<Candle>>()) } throws IndicatorComputeException("unsupported")

        val body =
            """
            {
              "spec": {"name": "unknown_ind"},
              "candles": [
                {"time": "2026-01-01T00:00:00Z", "open": 60000, "high": 61000, "low": 59000, "close": 60500, "volume": 1000000}
              ]
            }
            """.trimIndent()

        mvc
            .post("/api/indicators/compute") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isUnprocessableEntity() }
            }
    }

    @Test
    fun `POST compute invalid time format — 400`() {
        val body =
            """
            {
              "spec": {"name": "rsi"},
              "candles": [
                {"time": "NOT_A_VALID_TIME", "open": 60000, "high": 61000, "low": 59000, "close": 60500, "volume": 1000000}
              ]
            }
            """.trimIndent()

        mvc
            .post("/api/indicators/compute") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST compute MACD — multi-key values in series`() {
        val spec = IndicatorSpec("macd", mapOf("fast" to 12, "slow" to 26, "signal" to 9))
        val mockResult =
            IndicatorResult(
                spec = spec,
                series =
                    listOf(
                        IndicatorPoint(
                            Instant.parse("2026-01-02T00:00:00Z"),
                            mapOf(
                                "macd" to BigDecimal("1.23"),
                                "signal" to BigDecimal("0.45"),
                                "histogram" to BigDecimal("0.78"),
                            ),
                        ),
                    ),
                source = "native",
            )
        every { engine.calculate(any(), any<List<Candle>>()) } returns mockResult

        val body =
            """
            {
              "spec": {"name": "macd", "params": {"fast": 12, "slow": 26, "signal": 9}},
              "candles": [
                {"time": "2026-01-01T00:00:00Z", "open": 60000, "high": 61000, "low": 59000, "close": 60500, "volume": 1000000},
                {"time": "2026-01-02T00:00:00Z", "open": 60500, "high": 62000, "low": 60000, "close": 61500, "volume": 1200000}
              ]
            }
            """.trimIndent()

        mvc
            .post("/api/indicators/compute") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.series[0].values.macd") { value(1.23) }
                jsonPath("$.series[0].values.signal") { value(0.45) }
                jsonPath("$.series[0].values.histogram") { value(0.78) }
            }
    }
}

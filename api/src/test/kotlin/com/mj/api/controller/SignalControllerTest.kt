package com.mj.api.controller

import com.mj.api.application.signal.SignalHistoryService
import com.mj.api.config.WebSecurityConfig
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
import com.mj.domain.strategy.signal.SignalEngine
import com.mj.domain.strategy.signal.event.SignalGeneratedEvent
import org.junit.jupiter.api.BeforeEach
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
import java.time.Instant
import java.util.UUID

@WebMvcTest(
    controllers = [SignalController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class SignalControllerTest {
    @TestConfiguration
    class TestBeans {
        @Bean
        fun parser(): StrategyDslParser = StrategyDslParser()

        @Bean
        fun indicatorEngine(): IndicatorEngine = IndicatorEngine(listOf(NativeIndicatorCalculator()))

        @Bean
        fun evaluator(engine: IndicatorEngine): StrategyDslEvaluator = StrategyDslEvaluator(engine)

        @Bean
        fun signalEngine(evaluator: StrategyDslEvaluator): SignalEngine = SignalEngine(evaluator)

        @Bean
        fun history(): SignalHistoryService = SignalHistoryService()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var history: SignalHistoryService

    @BeforeEach
    fun resetHistory() {
        history.clear()
    }

    // ────────────────────────────────────────────────────────────
    // GET /api/signals
    // ────────────────────────────────────────────────────────────

    @Test
    fun `GET signals — empty history returns 200 with count 0`() {
        mvc.get("/api/signals").andExpect {
            status { isOk() }
            jsonPath("$.count") { value(0) }
            jsonPath("$.items.length()") { value(0) }
        }
    }

    @Test
    fun `GET signals — returns recorded entries newest first`() {
        history.record(sampleEvent(symbol = "005930", action = "BUY", at = Instant.parse("2026-05-03T09:00:00Z")))
        history.record(sampleEvent(symbol = "000660", action = "SELL", at = Instant.parse("2026-05-03T09:01:00Z")))

        mvc.get("/api/signals").andExpect {
            status { isOk() }
            jsonPath("$.count") { value(2) }
            jsonPath("$.items[0].symbol") { value("000660") } // newest
            jsonPath("$.items[0].action") { value("SELL") }
            jsonPath("$.items[1].symbol") { value("005930") }
        }
    }

    @Test
    fun `GET signals — userId filter`() {
        history.record(sampleEvent(symbol = "005930", action = "BUY", userId = "user-1"))
        history.record(sampleEvent(symbol = "000660", action = "SELL", userId = "user-2"))

        mvc.get("/api/signals?userId=user-1").andExpect {
            status { isOk() }
            jsonPath("$.count") { value(1) }
            jsonPath("$.items[0].userId") { value("user-1") }
        }
    }

    @Test
    fun `GET signals — limit param`() {
        for (i in 0 until 5) {
            history.record(sampleEvent(symbol = "00593$i", action = "BUY"))
        }
        mvc.get("/api/signals?limit=3").andExpect {
            status { isOk() }
            jsonPath("$.count") { value(3) }
        }
    }

    // ────────────────────────────────────────────────────────────
    // POST /api/signals/test
    // ────────────────────────────────────────────────────────────

    @Test
    fun `POST signals test — valid yaml returns BUY action`() {
        val body =
            """
            {
              "yaml": "version: \"1.0\"\nmetadata:\n  name: test\nstrategy:\n  id: test_buy\n  entry:\n    logic: AND\n    conditions:\n      - lhs: { price: close }\n        op: greater_than\n        rhs: { value: 100 }\n  exit:\n    logic: AND\n    conditions:\n      - lhs: { price: close }\n        op: less_than\n        rhs: { value: -1 }\n",
              "symbol": "005930",
              "candles": [
                { "time": "2026-01-01T00:00:00Z", "open": 110, "high": 120, "low": 105, "close": 115, "volume": 1000 }
              ]
            }
            """.trimIndent()

        mvc
            .post("/api/signals/test") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.action") { value("BUY") }
                jsonPath("$.strength") { value(1.0) }
                jsonPath("$.symbol") { value("005930") }
                jsonPath("$.strategyId") { value("test_buy") }
            }
    }

    @Test
    fun `POST signals test — empty candles returns 400`() {
        val body =
            """
            {
              "yaml": "version: \"1.0\"\nmetadata:\n  name: test\nstrategy:\n  id: test_buy\n  entry:\n    logic: AND\n    conditions:\n      - lhs: { price: close }\n        op: greater_than\n        rhs: { value: 100 }\n  exit:\n    logic: AND\n    conditions:\n      - lhs: { price: close }\n        op: less_than\n        rhs: { value: -1 }\n",
              "symbol": "005930",
              "candles": []
            }
            """.trimIndent()

        mvc
            .post("/api/signals/test") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST signals test — invalid yaml returns 400`() {
        val body =
            """
            {
              "yaml": "::: invalid yaml :::",
              "symbol": "005930",
              "candles": [
                { "time": "2026-01-01T00:00:00Z", "open": 110, "high": 120, "low": 105, "close": 115, "volume": 1000 }
              ]
            }
            """.trimIndent()

        mvc
            .post("/api/signals/test") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    // -------------------------------------------------------------------------
    // helpers
    // -------------------------------------------------------------------------

    private fun sampleEvent(
        symbol: String,
        action: String,
        at: Instant = Instant.parse("2026-05-03T09:00:00Z"),
        userId: String? = null,
    ): SignalGeneratedEvent =
        SignalGeneratedEvent(
            eventId = UUID.randomUUID(),
            strategyId = "test_strategy",
            symbol = symbol,
            action = action,
            strength = 1.0,
            generatedAt = at,
            evaluatedAt = at,
            reasons = listOf("test reason"),
            userId = userId,
        )
}

package com.mj.api.controller

import com.mj.api.config.WebSecurityConfig
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.api.infrastructure.dsl.StrategyDslPresets
import com.mj.domain.strategy.dsl.StrategyDslEvaluator
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.NativeIndicatorCalculator
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
    controllers = [StrategyController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class StrategyControllerTest {
    @TestConfiguration
    class TestBeans {
        @Bean
        fun parser(): StrategyDslParser = StrategyDslParser()

        @Bean
        fun presets(parser: StrategyDslParser): StrategyDslPresets = StrategyDslPresets(parser)

        @Bean
        fun indicatorEngine(): IndicatorEngine = IndicatorEngine(listOf(NativeIndicatorCalculator()))

        @Bean
        fun evaluator(engine: IndicatorEngine): StrategyDslEvaluator = StrategyDslEvaluator(engine)
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Test
    fun `GET presets — 200 with 10 entries`() {
        mvc.get("/api/strategies/presets").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(10) }
        }
    }

    @Test
    fun `GET preset by id — 200`() {
        mvc.get("/api/strategies/presets/golden_cross_5_20").andExpect {
            status { isOk() }
            jsonPath("$.id") { value("golden_cross_5_20") }
            jsonPath("$.indicators[0]") { value("ma") }
        }
    }

    @Test
    fun `GET preset unknown id — 404`() {
        mvc.get("/api/strategies/presets/nope_does_not_exist").andExpect {
            status { isNotFound() }
        }
    }

    @Test
    fun `POST validate — valid yaml returns valid=true`() {
        val yaml =
            """
            metadata:
              name: ok
            strategy:
              id: ok
              indicators:
                - id: rsi
                  alias: rsi14
                  params: { period: 14 }
              entry:
                logic: AND
                conditions:
                  - lhs: { alias: rsi14 }
                    op: less_than
                    rhs: { value: 30 }
              exit:
                logic: AND
                conditions:
                  - lhs: { alias: rsi14 }
                    op: greater_than
                    rhs: { value: 70 }
            """.trimIndent()
        val body = mapBody("yaml" to yaml)

        mvc
            .post("/api/strategies/validate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.valid") { value(true) }
                jsonPath("$.errors.length()") { value(0) }
            }
    }

    @Test
    fun `POST validate — unknown indicator returns valid=false`() {
        val yaml =
            """
            metadata:
              name: bad
            strategy:
              id: bad
              indicators:
                - id: definitely_not_an_indicator
                  alias: x
              entry:
                logic: AND
                conditions:
                  - lhs: { alias: x }
                    op: less_than
                    rhs: { value: 30 }
              exit:
                logic: AND
                conditions:
                  - lhs: { alias: x }
                    op: greater_than
                    rhs: { value: 70 }
            """.trimIndent()
        val body = mapBody("yaml" to yaml)

        mvc
            .post("/api/strategies/validate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.valid") { value(false) }
            }
    }

    @Test
    fun `POST validate — bad yaml returns parseError`() {
        val body = mapBody("yaml" to "this is not yaml [[[")

        mvc
            .post("/api/strategies/validate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.valid") { value(false) }
                jsonPath("$.parseError") { exists() }
            }
    }

    @Test
    fun `POST parse — 200 with normalizedYaml`() {
        val yaml =
            """
            metadata:
              name: rt
            strategy:
              id: rt
              indicators:
                - id: ma
                  alias: ma20
                  params: { period: 20 }
              entry:
                logic: AND
                conditions:
                  - lhs: close
                    op: greater_than
                    rhs: { alias: ma20 }
              exit:
                logic: AND
                conditions:
                  - lhs: close
                    op: less_than
                    rhs: { alias: ma20 }
            """.trimIndent()
        val body = mapBody("yaml" to yaml)

        mvc
            .post("/api/strategies/parse") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.id") { value("rt") }
                jsonPath("$.normalizedYaml") { exists() }
                jsonPath("$.validation.valid") { value(true) }
            }
    }

    @Test
    fun `POST evaluate with synthetic candles — 200`() {
        val yaml =
            """
            metadata:
              name: simple
            strategy:
              id: simple
              indicators: []
              entry:
                logic: AND
                conditions:
                  - lhs: close
                    op: greater_than
                    rhs: { value: 100 }
              exit:
                logic: AND
                conditions:
                  - lhs: close
                    op: less_than
                    rhs: { value: 50 }
            """.trimIndent()

        val candlesJson =
            buildString {
                append("[")
                for (i in 0 until 30) {
                    if (i > 0) append(",")
                    val close = 110 + i
                    append(
                        """{"time":"2026-01-${"%02d".format(
                            i + 1,
                        )}T00:00:00Z","open":$close,"high":${close + 1},"low":${close - 1},"close":$close,"volume":1000}""",
                    )
                }
                append("]")
            }

        val body =
            """{"yaml": ${jsonString(yaml)}, "candles": $candlesJson}"""

        mvc
            .post("/api/strategies/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.strategyId") { value("simple") }
                jsonPath("$.entry") { value(true) }
                jsonPath("$.exit") { value(false) }
            }
    }

    @Test
    fun `POST evaluate with empty candles — 400`() {
        val yaml =
            """
            metadata:
              name: simple
            strategy:
              id: simple
              indicators: []
              entry:
                logic: AND
                conditions:
                  - lhs: close
                    op: greater_than
                    rhs: { value: 100 }
              exit:
                logic: AND
                conditions:
                  - lhs: close
                    op: less_than
                    rhs: { value: 50 }
            """.trimIndent()
        val body = """{"yaml": ${jsonString(yaml)}, "candles": []}"""

        mvc
            .post("/api/strategies/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST evaluate with invalid yaml strategy — 422`() {
        val yaml =
            """
            metadata:
              name: bad
            strategy:
              id: bad
              indicators:
                - id: garbage
                  alias: g
              entry:
                logic: AND
                conditions:
                  - lhs: { alias: g }
                    op: less_than
                    rhs: { value: 30 }
              exit:
                logic: AND
                conditions:
                  - lhs: close
                    op: less_than
                    rhs: { value: 0 }
            """.trimIndent()
        val candlesJson =
            """[{"time":"2026-01-01T00:00:00Z","open":100,"high":101,"low":99,"close":100,"volume":1000}]"""
        val body =
            """{"yaml": ${jsonString(yaml)}, "candles": $candlesJson}"""

        mvc
            .post("/api/strategies/evaluate") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isUnprocessableEntity() }
            }
    }

    private fun mapBody(vararg pairs: Pair<String, String>): String =
        pairs.joinToString(",", "{", "}") { (k, v) -> "${jsonString(k)}: ${jsonString(v)}" }

    private fun jsonString(s: String): String {
        val escaped =
            s
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        return "\"$escaped\""
    }
}

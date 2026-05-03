package com.mj.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.config.WebSecurityConfig
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.autoconfigure.data.jpa.JpaRepositoriesAutoConfiguration
import org.springframework.boot.autoconfigure.flyway.FlywayAutoConfiguration
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
import org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post

@WebMvcTest(
    controllers = [PatternController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class PatternControllerTest {
    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var objectMapper: ObjectMapper

    // ─────────────────────────────────────────────
    // GET /api/patterns
    // ─────────────────────────────────────────────

    @Test
    fun `GET catalog — 200 with 57 entries`() {
        mvc.get("/api/patterns").andExpect {
            status { isOk() }
            jsonPath("$.length()") { value(57) }
        }
    }

    @Test
    fun `GET catalog filter category=BULLISH_REVERSAL`() {
        mvc.get("/api/patterns?category=BULLISH_REVERSAL").andExpect {
            status { isOk() }
            jsonPath("$[0].category") { value("BULLISH_REVERSAL") }
        }
    }

    @Test
    fun `GET catalog category=lowercase 허용`() {
        mvc.get("/api/patterns?category=indecision").andExpect {
            status { isOk() }
            jsonPath("$[0].category") { value("INDECISION") }
        }
    }

    @Test
    fun `GET catalog 잘못된 category — 400`() {
        mvc.get("/api/patterns?category=INVALID").andExpect {
            status { isBadRequest() }
        }
    }

    @Test
    fun `GET catalog candleCount=3`() {
        mvc.get("/api/patterns?candleCount=3").andExpect {
            status { isOk() }
            // 모든 응답 항목 candleCount=3
            jsonPath("$[*].candleCount") { value(org.hamcrest.Matchers.everyItem(org.hamcrest.Matchers.equalTo(3))) }
        }
    }

    @Test
    fun `GET catalog candleCount=0 — 400`() {
        mvc.get("/api/patterns?candleCount=0").andExpect {
            status { isBadRequest() }
        }
    }

    // ─────────────────────────────────────────────
    // POST /api/patterns/recognize
    // ─────────────────────────────────────────────

    @Test
    fun `POST recognize — 도지 캔들 1개 매칭`() {
        val body =
            """
            {
              "candles": [
                {"time":"2026-01-02T00:00:00Z","open":1000,"high":1010,"low":990,"close":1000,"volume":100000}
              ],
              "lookback": 50
            }
            """.trimIndent()

        mvc
            .post("/api/patterns/recognize") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.candleCount") { value(1) }
                jsonPath("$.matches[0].patternCode") { value("doji") }
                jsonPath("$.matches[0].direction") { value("NEUTRAL") }
            }
    }

    @Test
    fun `POST recognize — 빈 candles 400`() {
        val body = """{"candles":[]}"""
        mvc
            .post("/api/patterns/recognize") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST recognize — 잘못된 패턴 코드 400`() {
        val body =
            """
            {
              "candles": [
                {"time":"2026-01-02T00:00:00Z","open":1000,"high":1010,"low":990,"close":1000,"volume":100000}
              ],
              "patterns": ["not_a_pattern"]
            }
            """.trimIndent()
        mvc
            .post("/api/patterns/recognize") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST recognize — lookback 음수 400`() {
        val body =
            """
            {
              "candles": [
                {"time":"2026-01-02T00:00:00Z","open":1000,"high":1010,"low":990,"close":1000,"volume":100000}
              ],
              "lookback": -1
            }
            """.trimIndent()
        mvc
            .post("/api/patterns/recognize") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST recognize — 시간 파싱 오류 400`() {
        val body =
            """
            {
              "candles": [
                {"time":"not-an-iso","open":1000,"high":1010,"low":990,"close":1000,"volume":100000}
              ]
            }
            """.trimIndent()
        mvc
            .post("/api/patterns/recognize") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST recognize — 특정 패턴만 검사 (samsung 도지)`() {
        val body =
            """
            {
              "candles": [
                {"time":"2026-01-02T00:00:00Z","open":1000,"high":1010,"low":990,"close":1000,"volume":100000}
              ],
              "patterns": ["doji"]
            }
            """.trimIndent()
        mvc
            .post("/api/patterns/recognize") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andExpect {
                status { isOk() }
                jsonPath("$.matches.length()") { value(1) }
                jsonPath("$.matches[0].patternCode") { value("doji") }
            }
    }
}

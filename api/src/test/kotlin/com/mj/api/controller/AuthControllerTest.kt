package com.mj.api.controller

import com.fasterxml.jackson.databind.ObjectMapper
import com.mj.api.application.auth.AuthService
import com.mj.api.application.auth.AuthStatusResult
import com.mj.api.application.auth.KisMode
import com.mj.api.application.auth.LoginRequest
import com.mj.api.application.auth.LoginResult
import com.mj.api.application.auth.ModeChangeRequest
import com.mj.api.application.auth.ModeChangeResult
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
import java.time.OffsetDateTime
import java.util.UUID

@WebMvcTest(
    controllers = [AuthController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class AuthControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun authService(): AuthService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var authService: AuthService

    @Autowired
    lateinit var objectMapper: ObjectMapper

    private val defaultUserId = UUID.fromString("00000000-0000-0000-0000-000000000001")

    // ─── GET /api/auth/status ─────────────────────────────────────────────────

    @Test
    fun `GET api auth status returns authenticated true when credentials set`() {
        every { authService.status(defaultUserId) } returns
            AuthStatusResult(
                authenticated = true,
                mode = KisMode.Vps,
                accountNo = "5000123456",
                hasAppKey = true,
                hasAppSecret = true,
                lastUpdatedAt = OffsetDateTime.parse("2026-05-02T12:00:00+09:00"),
            )

        mvc
            .get("/api/auth/status")
            .andExpect {
                status { isOk() }
                jsonPath("$.authenticated") { value(true) }
                jsonPath("$.mode") { value("vps") }
                jsonPath("$.accountNo") { value("5000123456") }
                jsonPath("$.hasAppKey") { value(true) }
                jsonPath("$.hasAppSecret") { value(true) }
            }
    }

    @Test
    fun `GET api auth status returns authenticated false when no credentials`() {
        every { authService.status(defaultUserId) } returns
            AuthStatusResult(
                authenticated = false,
                mode = null,
                accountNo = null,
                hasAppKey = false,
                hasAppSecret = false,
                lastUpdatedAt = null,
            )

        mvc
            .get("/api/auth/status")
            .andExpect {
                status { isOk() }
                jsonPath("$.authenticated") { value(false) }
                jsonPath("$.mode") { doesNotExist() }
                jsonPath("$.accountNo") { doesNotExist() }
                jsonPath("$.hasAppKey") { value(false) }
            }
    }

    // ─── POST /api/auth/login ─────────────────────────────────────────────────

    @Test
    fun `POST api auth login saves credentials and returns authenticated`() {
        every {
            authService.login(
                defaultUserId,
                LoginRequest(
                    mode = KisMode.Vps,
                    appKey = "PSYmn3sPTESTKEY123",
                    appSecret = "TESTSECRETHASHVALUE12345",
                    accountNo = "5000123456",
                    accountProductCode = "01",
                    htsId = null,
                ),
            )
        } returns LoginResult(authenticated = true, mode = KisMode.Vps)

        val body =
            mapOf(
                "mode" to "vps",
                "appKey" to "PSYmn3sPTESTKEY123",
                "appSecret" to "TESTSECRETHASHVALUE12345",
                "accountNo" to "5000123456",
            )

        mvc
            .post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(body)
            }.andExpect {
                status { isOk() }
                jsonPath("$.authenticated") { value(true) }
                jsonPath("$.mode") { value("vps") }
            }
    }

    @Test
    fun `POST api auth login returns 400 for invalid mode`() {
        mvc
            .post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "mode" to "invalid_mode",
                            "appKey" to "PSYmn3sPTESTKEY123",
                            "appSecret" to "TESTSECRETHASHVALUE12345",
                            "accountNo" to "5000123456",
                        ),
                    )
            }.andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST api auth login returns 400 when appKey missing`() {
        mvc
            .post("/api/auth/login") {
                contentType = MediaType.APPLICATION_JSON
                content =
                    objectMapper.writeValueAsString(
                        mapOf(
                            "mode" to "vps",
                            "appSecret" to "TESTSECRETHASHVALUE12345",
                            "accountNo" to "5000123456",
                        ),
                    )
            }.andExpect {
                status { isBadRequest() }
            }
    }

    // ─── POST /api/auth/mode ──────────────────────────────────────────────────

    @Test
    fun `POST api auth mode switches to prod successfully`() {
        every { authService.switchMode(defaultUserId, ModeChangeRequest(KisMode.Prod)) } returns
            ModeChangeResult(success = true, mode = KisMode.Prod)

        mvc
            .post("/api/auth/mode") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("mode" to "prod"))
            }.andExpect {
                status { isOk() }
                jsonPath("$.success") { value(true) }
                jsonPath("$.mode") { value("prod") }
            }
    }

    @Test
    fun `POST api auth mode returns success false when no credentials`() {
        every { authService.switchMode(defaultUserId, ModeChangeRequest(KisMode.Vps)) } returns
            ModeChangeResult(success = false, reason = "자격증명이 설정되지 않았습니다. 먼저 /api/auth/login을 호출하세요.")

        mvc
            .post("/api/auth/mode") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("mode" to "vps"))
            }.andExpect {
                status { isOk() }
                jsonPath("$.success") { value(false) }
                jsonPath("$.reason") { value("자격증명이 설정되지 않았습니다. 먼저 /api/auth/login을 호출하세요.") }
            }
    }

    @Test
    fun `POST api auth mode returns 400 for invalid mode value`() {
        mvc
            .post("/api/auth/mode") {
                contentType = MediaType.APPLICATION_JSON
                content = objectMapper.writeValueAsString(mapOf("mode" to "UNKNOWN"))
            }.andExpect {
                status { isBadRequest() }
            }
    }
}

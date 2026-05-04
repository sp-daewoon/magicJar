package com.mj.api.controller

import com.mj.api.config.WebSecurityConfig
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import com.mj.domain.market.session.port.MarketSessionResolver
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
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * MarketSessionController WebMvcTest — plan T2-R2-A3 §T18.
 */
@WebMvcTest(
    controllers = [MarketSessionController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class MarketSessionControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun sessionResolver(): MarketSessionResolver = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var sessionResolver: MarketSessionResolver

    @Test
    fun `GET session Open — 200 + state Open + lastBusinessDate`() {
        val today = LocalDate.of(2026, 5, 4)
        every { sessionResolver.resolve() } returns
            MarketSession(
                state = MarketSessionState.Open,
                kstAt = OffsetDateTime.of(today.atTime(11, 0), ZoneOffset.ofHours(9)),
                lastBusinessDate = today,
                nextOpenAt = null,
            )

        mvc
            .get("/api/market/session")
            .andExpect {
                status { isOk() }
                jsonPath("$.state") { value("Open") }
                jsonPath("$.lastBusinessDate") { value("2026-05-04") }
                jsonPath("$.nextOpenAt") { doesNotExist() }
            }
    }

    @Test
    fun `GET session Closed — 200 + state Closed + nextOpenAt`() {
        val sat = LocalDate.of(2026, 5, 9)
        val nextMon = LocalDate.of(2026, 5, 11)
        every { sessionResolver.resolve() } returns
            MarketSession(
                state = MarketSessionState.Closed("Saturday"),
                kstAt = OffsetDateTime.of(sat.atTime(20, 0), ZoneOffset.ofHours(9)),
                lastBusinessDate = sat.minusDays(1),
                nextOpenAt = OffsetDateTime.of(nextMon.atTime(9, 0), ZoneOffset.ofHours(9)),
            )

        mvc
            .get("/api/market/session")
            .andExpect {
                status { isOk() }
                jsonPath("$.state") { value("Closed") }
                jsonPath("$.lastBusinessDate") { value("2026-05-08") }
            }
    }

    @Test
    fun `GET session Holiday — 200 + state Holiday`() {
        val today = LocalDate.of(2026, 5, 5)
        every { sessionResolver.resolve() } returns
            MarketSession(
                state = MarketSessionState.Holiday(name = "어린이날", reason = "법정공휴일"),
                kstAt = OffsetDateTime.of(today.atTime(11, 0), ZoneOffset.ofHours(9)),
                lastBusinessDate = today.minusDays(1),
                nextOpenAt = OffsetDateTime.of(today.plusDays(1).atTime(9, 0), ZoneOffset.ofHours(9)),
            )

        mvc
            .get("/api/market/session")
            .andExpect {
                status { isOk() }
                jsonPath("$.state") { value("Holiday") }
            }
    }
}

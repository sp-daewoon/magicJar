package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.common.Instrument
import com.mj.domain.common.Market
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

@WebMvcTest(
    controllers = [InstrumentController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class InstrumentControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun instrumentService(): InstrumentService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var instrumentService: InstrumentService

    @Test
    fun `GET api instruments returns 200 with list`() {
        every { instrumentService.list() } returns
            listOf(
                Instrument("005930", "삼성전자", Market.KOSPI),
            )

        mvc
            .get("/api/instruments")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("005930") }
                jsonPath("$[0].name") { value("삼성전자") }
            }
    }

    @Test
    fun `GET api instruments code returns 200 for existing`() {
        every { instrumentService.get("005930") } returns Instrument("005930", "삼성전자", Market.KOSPI)

        mvc
            .get("/api/instruments/005930")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
            }
    }

    @Test
    fun `GET api instruments code returns 404 for unknown`() {
        every { instrumentService.get("XXXXX") } returns null

        mvc
            .get("/api/instruments/xxxxx")
            .andExpect {
                status { isNotFound() }
            }
    }
}

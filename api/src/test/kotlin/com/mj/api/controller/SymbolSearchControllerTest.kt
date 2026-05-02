package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
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
import java.math.BigDecimal
import java.time.LocalDate

@WebMvcTest(
    controllers = [SymbolSearchController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class SymbolSearchControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun instrumentService(): InstrumentService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var instrumentService: InstrumentService

    private fun buildInstrument(
        code: String,
        name: String,
        market: Market,
    ) = Instrument(
        code = code,
        isin = null,
        name = name,
        nameEn = null,
        market = market,
        type = InstrumentType.STOCK,
        sector = null,
        listedAt = LocalDate.of(2000, 1, 1),
        delistedAt = null,
        parValue = BigDecimal("5000"),
        tradingUnit = 1,
        active = true,
    )

    @Test
    fun `GET api symbols search returns matching instruments`() {
        every { instrumentService.search("삼성") } returns
            listOf(
                buildInstrument("005930", "삼성전자", Market.KOSPI),
                buildInstrument("005935", "삼성전자우", Market.KOSPI),
            )

        mvc
            .get("/api/symbols/search?q=삼성")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].code") { value("005930") }
            }
    }

    @Test
    fun `GET api symbols search with empty query returns empty list`() {
        every { instrumentService.search("") } returns emptyList()

        mvc
            .get("/api/symbols/search")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }
}

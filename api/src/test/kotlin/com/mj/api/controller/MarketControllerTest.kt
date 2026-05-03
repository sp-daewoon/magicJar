package com.mj.api.controller

import com.mj.api.application.market.MarketIndexService
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.market.index.MarketIndex
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

/**
 * MarketController 단위 테스트 (T21-T3).
 */
@WebMvcTest(
    controllers = [MarketController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class MarketControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun marketIndexService(): MarketIndexService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var marketIndexService: MarketIndexService

    private fun kospiIndex() =
        MarketIndex(
            code = "0001",
            name = "KOSPI",
            currentValue = BigDecimal("2650.34"),
            prevClose = BigDecimal("2630.12"),
            changePercent = BigDecimal("0.77"),
            volume = 450_123_456L,
            session = "장중",
            tradingValue = BigDecimal("8500000000000"),
        )

    private fun kosdaqIndex() =
        MarketIndex(
            code = "1001",
            name = "KOSDAQ",
            currentValue = BigDecimal("880.12"),
            prevClose = BigDecimal("875.50"),
            changePercent = BigDecimal("0.53"),
            volume = 120_456_789L,
        )

    @Test
    fun `GET api market index returns KOSPI and KOSDAQ by default`() {
        every { marketIndexService.getIndexes(listOf("0001", "1001")) } returns
            listOf(kospiIndex(), kosdaqIndex())

        mvc
            .get("/api/market/index")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(2) }
                jsonPath("$[0].code") { value("0001") }
                jsonPath("$[0].name") { value("KOSPI") }
                jsonPath("$[0].currentValue") { value(2650.34) }
                jsonPath("$[0].changePercent") { value(0.77) }
                jsonPath("$[1].code") { value("1001") }
                jsonPath("$[1].name") { value("KOSDAQ") }
            }
    }

    @Test
    fun `GET api market index with codes param returns only requested index`() {
        every { marketIndexService.getIndexes(listOf("0001")) } returns listOf(kospiIndex())

        mvc
            .get("/api/market/index?codes=KOSPI")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].code") { value("0001") }
            }
    }

    @Test
    fun `GET api market index returns empty list when KIS key not set`() {
        every { marketIndexService.getIndexes(listOf("0001", "1001")) } returns emptyList()

        mvc
            .get("/api/market/index")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    @Test
    fun `GET api market index accepts numeric codes like 0001`() {
        every { marketIndexService.getIndexes(listOf("0001")) } returns listOf(kospiIndex())

        mvc
            .get("/api/market/index?codes=0001")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].name") { value("KOSPI") }
            }
    }
}

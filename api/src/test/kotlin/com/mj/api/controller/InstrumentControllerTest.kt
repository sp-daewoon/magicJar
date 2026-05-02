package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.config.WebSecurityConfig
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
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
import java.time.Instant
import java.time.LocalDate

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

        @Bean
        fun candleQueryService(): CandleQueryService = mockk()
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var instrumentService: InstrumentService

    @Autowired
    lateinit var candleQueryService: CandleQueryService

    private fun samsungInstrument() =
        Instrument(
            code = "005930",
            isin = "KR7005930003",
            name = "삼성전자",
            nameEn = "Samsung Electronics",
            market = Market.KOSPI,
            type = InstrumentType.STOCK,
            sector = "전기·전자",
            listedAt = LocalDate.of(1975, 6, 11),
            delistedAt = null,
            parValue = BigDecimal("100"),
            tradingUnit = 1,
            active = true,
        )

    @Test
    fun `GET api instruments returns 200 with list`() {
        every { instrumentService.list() } returns listOf(samsungInstrument())

        mvc
            .get("/api/instruments")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("005930") }
                jsonPath("$[0].name") { value("삼성전자") }
            }
    }

    @Test
    fun `GET api instruments search returns 200`() {
        every { instrumentService.search("삼성", 20) } returns listOf(samsungInstrument())

        mvc
            .get("/api/instruments/search?q=삼성")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("005930") }
                jsonPath("$[0].name") { value("삼성전자") }
                jsonPath("$[0].market") { value("KOSPI") }
            }
    }

    @Test
    fun `GET api instruments search limit over 100 returns 400`() {
        mvc
            .get("/api/instruments/search?q=삼성&limit=101")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET api instruments code returns 200 for existing`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

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

    @Test
    fun `GET api instruments code candles returns 200 with array`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { candleQueryService.query("005930", Interval.D1, null, null) } returns
            listOf(
                Candle(
                    code = "005930",
                    interval = Interval.D1,
                    openTime = Instant.parse("2026-04-30T00:00:00Z"),
                    open = BigDecimal("60000"),
                    high = BigDecimal("60500"),
                    low = BigDecimal("59500"),
                    close = BigDecimal("60200"),
                    volume = 12_345_678L,
                    tradeCount = 100_000L,
                ),
            )

        mvc
            .get("/api/instruments/005930/candles")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].time") { value("2026-04-30") }
                jsonPath("$[0].open") { value(60000) }
                jsonPath("$[0].high") { value(60500) }
                jsonPath("$[0].low") { value(59500) }
                jsonPath("$[0].close") { value(60200) }
                jsonPath("$[0].volume") { value(12_345_678) }
            }
    }

    @Test
    fun `GET api instruments code candles returns 404 for unknown code`() {
        every { instrumentService.get("XXXXX") } returns null

        mvc
            .get("/api/instruments/xxxxx/candles")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET api instruments code candles returns 400 for invalid interval`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

        mvc
            .get("/api/instruments/005930/candles?interval=BOGUS")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET api instruments code candles M1 returns time as JSON number (epoch second)`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { candleQueryService.query("005930", Interval.M1, null, null) } returns
            listOf(
                Candle(
                    code = "005930",
                    interval = Interval.M1,
                    openTime = Instant.parse("2026-04-30T05:30:00Z"), // = epoch 1777527000
                    open = BigDecimal("60100"),
                    high = BigDecimal("60150"),
                    low = BigDecimal("60050"),
                    close = BigDecimal("60120"),
                    volume = 1_234L,
                    tradeCount = 10L,
                ),
            )

        mvc
            .get("/api/instruments/005930/candles?interval=M1")
            .andExpect {
                status { isOk() }
                // LWC UTCTimestamp 호환 — 분봉은 number(Long)로 직렬화 (string 아님)
                jsonPath("$[0].time") { value(1_777_527_000) }
                jsonPath("$[0].open") { value(60_100) }
            }
    }

    @Test
    fun `GET api instruments code candles accepts DAY alias and empty result`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { candleQueryService.query("005930", Interval.D1, null, null) } returns emptyList()

        mvc
            .get("/api/instruments/005930/candles?interval=DAY")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }
}

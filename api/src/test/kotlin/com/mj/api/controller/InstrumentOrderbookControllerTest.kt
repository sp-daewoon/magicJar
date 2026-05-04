package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentMasterSyncApiService
import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.CandleCacheService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.application.market.InstrumentDetailService
import com.mj.api.application.market.InvestorTrendService
import com.mj.api.application.market.OrderbookService
import com.mj.api.application.market.RecentTradesService
import com.mj.api.config.WebSecurityConfig
import com.mj.api.messaging.producer.MarketCandleBackfillCommandPublisher
import com.mj.domain.common.Market
import com.mj.domain.market.InvestorTrend
import com.mj.domain.market.Orderbook
import com.mj.domain.market.OrderbookLevel
import com.mj.domain.market.RecentTrade
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
class InstrumentOrderbookControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean fun instrumentService(): InstrumentService = mockk()

        @Bean fun candleQueryService(): CandleQueryService = mockk()

        @Bean fun candleCacheService(): CandleCacheService = mockk()

        @Bean fun instrumentMasterSyncApiService(): InstrumentMasterSyncApiService = mockk()

        @Bean fun instrumentDetailService(): InstrumentDetailService = mockk()

        @Bean fun orderbookService(): OrderbookService = mockk()

        @Bean fun recentTradesService(): RecentTradesService = mockk()

        @Bean fun investorTrendService(): InvestorTrendService = mockk()

        @Bean fun marketCandleBackfillCommandPublisher(): MarketCandleBackfillCommandPublisher = mockk(relaxed = true)
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var instrumentService: InstrumentService

    @Autowired lateinit var orderbookService: OrderbookService

    @Autowired lateinit var recentTradesService: RecentTradesService

    @Autowired lateinit var investorTrendService: InvestorTrendService

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

    // ── 호가창 테스트 ─────────────────────────────────────────────────────────

    @Test
    fun `GET api instruments code orderbook returns 200 with ask and bid levels`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { orderbookService.getOrderbook("005930") } returns
            Orderbook(
                code = "005930",
                acceptedAt = Instant.parse("2026-05-04T06:59:59Z"),
                askLevels = listOf(OrderbookLevel(BigDecimal("221000"), 10013L, -500L)),
                bidLevels = listOf(OrderbookLevel(BigDecimal("220500"), 377116L, 1000L)),
                totalAskQty = 500000L,
                totalBidQty = 800000L,
                currentPrice = BigDecimal("220500"),
                anticipatedPrice = BigDecimal("221000"),
                anticipatedVolume = 5000L,
            )

        mvc
            .get("/api/instruments/005930/orderbook")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.askLevels[0].price") { value(221000) }
                jsonPath("$.askLevels[0].qty") { value(10013) }
                jsonPath("$.bidLevels[0].price") { value(220500) }
                jsonPath("$.bidLevels[0].qty") { value(377116) }
                jsonPath("$.totalAskQty") { value(500000) }
                jsonPath("$.totalBidQty") { value(800000) }
                jsonPath("$.currentPrice") { value(220500) }
            }
    }

    @Test
    fun `GET api instruments code orderbook returns 404 for unknown code`() {
        every { instrumentService.get("999999") } returns null

        mvc
            .get("/api/instruments/999999/orderbook")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET api instruments code orderbook returns 404 when KIS key not set`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { orderbookService.getOrderbook("005930") } returns null

        mvc
            .get("/api/instruments/005930/orderbook")
            .andExpect {
                status { isNotFound() }
            }
    }

    // ── 체결 list 테스트 ───────────────────────────────────────────────────────

    @Test
    fun `GET api instruments code trades returns 200 with trade list`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { recentTradesService.getRecentTrades("005930", 30) } returns
            listOf(
                RecentTrade(
                    code = "005930",
                    tradeTime = "155959",
                    price = BigDecimal("220500"),
                    volume = 2L,
                    priceChange = BigDecimal("-5500"),
                    priceChangeSign = "5",
                    changeRate = BigDecimal("-2.43"),
                ),
            )

        mvc
            .get("/api/instruments/005930/trades")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("005930") }
                jsonPath("$[0].tradeTime") { value("155959") }
                jsonPath("$[0].price") { value(220500) }
                jsonPath("$[0].volume") { value(2) }
                jsonPath("$[0].priceChangeSign") { value("5") }
            }
    }

    @Test
    fun `GET api instruments code trades returns 404 for unknown code`() {
        every { instrumentService.get("999999") } returns null

        mvc
            .get("/api/instruments/999999/trades")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET api instruments code trades returns 400 for limit over 30`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

        mvc
            .get("/api/instruments/005930/trades?limit=31")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET api instruments code trades returns 200 with empty list when KIS key not set`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { recentTradesService.getRecentTrades("005930", 30) } returns emptyList()

        mvc
            .get("/api/instruments/005930/trades")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }

    // ── 외국인·기관 동향 테스트 ────────────────────────────────────────────────

    @Test
    fun `GET api instruments code investors returns 200 with trend list`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { investorTrendService.getInvestorTrend("005930", 20) } returns
            listOf(
                InvestorTrend(
                    code = "005930",
                    bizDate = "20260430",
                    closePrice = BigDecimal("220500"),
                    priceChange = BigDecimal("-5500"),
                    priceChangeSign = "5",
                    personNetBuyQty = 617606L,
                    foreignNetBuyQty = -840944L,
                    orgNetBuyQty = 158634L,
                    personNetBuyAmt = BigDecimal("129042"),
                    foreignNetBuyAmt = BigDecimal("-173923"),
                    orgNetBuyAmt = BigDecimal("30727"),
                    personBuyVol = 6483445L,
                    foreignBuyVol = 7619033L,
                    orgBuyVol = 7868939L,
                    personSellVol = 5865839L,
                    foreignSellVol = 8459977L,
                    orgSellVol = 7710305L,
                ),
            )

        mvc
            .get("/api/instruments/005930/investors")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].code") { value("005930") }
                jsonPath("$[0].bizDate") { value("20260430") }
                jsonPath("$[0].closePrice") { value(220500) }
                jsonPath("$[0].foreignNetBuyQty") { value(-840944) }
                jsonPath("$[0].orgNetBuyQty") { value(158634) }
            }
    }

    @Test
    fun `GET api instruments code investors returns 404 for unknown code`() {
        every { instrumentService.get("999999") } returns null

        mvc
            .get("/api/instruments/999999/investors")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET api instruments code investors returns 400 for days over 30`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

        mvc
            .get("/api/instruments/005930/investors?days=31")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET api instruments code investors returns 200 with empty list when KIS key not set`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { investorTrendService.getInvestorTrend("005930", 20) } returns emptyList()

        mvc
            .get("/api/instruments/005930/investors")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
            }
    }
}

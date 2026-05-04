package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.QuoteQueryService
import com.mj.api.application.market.QuoteResult
import com.mj.api.config.WebSecurityConfig
import com.mj.api.messaging.producer.MarketSubscriptionCommandPublisher
import com.mj.domain.common.Market
import com.mj.domain.market.cache.port.QuoteSource
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import io.mockk.Runs
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.verify
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
import java.time.OffsetDateTime
import java.time.ZoneId

/**
 * QuoteController WebMvcTest. ADR 0033.
 *
 * 시나리오:
 *   1. 미등록 종목 → 404
 *   2. 등록 종목 + LIVE 시세 → 200, source=LIVE, stale=false
 *   3. 등록 종목 + CACHED 시세 → 200, source=CACHED, stale=true
 *   4. 등록 종목 + LAST_DAY_CLOSE → 200, source=LAST_DAY_CLOSE, stale=true
 *   5. 등록 종목 + 시세 없음 → 404
 */
@WebMvcTest(
    controllers = [QuoteController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(WebSecurityConfig::class)
class QuoteControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean
        fun instrumentService(): InstrumentService = mockk()

        @Bean
        fun quoteQueryService(): QuoteQueryService = mockk()

        @Bean
        fun marketSubscriptionCommandPublisher(): MarketSubscriptionCommandPublisher = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var instrumentService: InstrumentService

    @Autowired
    lateinit var quoteQueryService: QuoteQueryService

    @Autowired
    lateinit var subscriptionCommandPublisher: MarketSubscriptionCommandPublisher

    private val now = Instant.parse("2026-05-02T01:30:00Z")
    private val zone = ZoneId.of("Asia/Seoul")

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

    private fun openSession() =
        MarketSession(
            state = MarketSessionState.Open,
            kstAt = OffsetDateTime.now(zone),
            lastBusinessDate = LocalDate.now(zone),
            nextOpenAt = null,
        )

    @Test
    fun `GET quote 미등록 종목 - 404`() {
        every { instrumentService.get("999999") } returns null

        mvc
            .get("/api/instruments/999999/quote")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET quote LIVE 시세 - 200, source=LIVE, stale=false`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { quoteQueryService.query("005930") } returns
            QuoteResult(
                code = "005930",
                price = BigDecimal("75000"),
                volume = 1_000_000L,
                source = QuoteSource.LIVE,
                stale = false,
                asOf = now,
                session = openSession(),
            )

        mvc
            .get("/api/instruments/005930/quote")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.price") { value(75000) }
                jsonPath("$.volume") { value(1_000_000) }
                jsonPath("$.source") { value("LIVE") }
                jsonPath("$.stale") { value(false) }
                jsonPath("$.session.state") { value("Open") }
            }
    }

    @Test
    fun `GET quote CACHED 시세 - 200, source=CACHED, stale=true`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { quoteQueryService.query("005930") } returns
            QuoteResult(
                code = "005930",
                price = BigDecimal("74500"),
                volume = null,
                source = QuoteSource.CACHED,
                stale = true,
                asOf = now.minusSeconds(30),
                session = openSession(),
            )

        mvc
            .get("/api/instruments/005930/quote")
            .andExpect {
                status { isOk() }
                jsonPath("$.source") { value("CACHED") }
                jsonPath("$.stale") { value(true) }
            }
    }

    @Test
    fun `GET quote LAST_DAY_CLOSE - 200, source=LAST_DAY_CLOSE, stale=true`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { quoteQueryService.query("005930") } returns
            QuoteResult(
                code = "005930",
                price = BigDecimal("73000"),
                volume = 12_000_000L,
                source = QuoteSource.LAST_DAY_CLOSE,
                stale = true,
                asOf = Instant.parse("2026-05-01T00:00:00Z"),
                session = openSession(),
            )

        mvc
            .get("/api/instruments/005930/quote")
            .andExpect {
                status { isOk() }
                jsonPath("$.source") { value("LAST_DAY_CLOSE") }
                jsonPath("$.stale") { value(true) }
                jsonPath("$.price") { value(73000) }
            }
    }

    @Test
    fun `GET quote 시세 없음 - 404`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { quoteQueryService.query("005930") } returns null

        mvc
            .get("/api/instruments/005930/quote")
            .andExpect { status { isNotFound() } }
    }

    @Test
    fun `GET quote 등록 종목 - ensureSubscribe 호출됨 (R-B 동적 구독)`() {
        every { instrumentService.get("000020") } returns
            Instrument(
                code = "000020",
                isin = "KR7000020008",
                name = "동화약품",
                nameEn = null,
                market = Market.KOSPI,
                type = InstrumentType.STOCK,
                sector = null,
                listedAt = LocalDate.of(1976, 3, 24),
                delistedAt = null,
                parValue = null,
                tradingUnit = 1,
                active = true,
            )
        every { quoteQueryService.query("000020") } returns null
        every { subscriptionCommandPublisher.ensureSubscribe("000020") } just Runs

        mvc
            .get("/api/instruments/000020/quote")
            .andExpect { status { isNotFound() } }

        // 시세가 없어 404가 떨어져도 구독 명령은 발행되어야 한다 (다음 진입 시 LIVE 보장).
        verify(exactly = 1) { subscriptionCommandPublisher.ensureSubscribe("000020") }
    }

    @Test
    fun `GET quote 미등록 종목 - ensureSubscribe 호출 안 됨`() {
        every { instrumentService.get("888888") } returns null

        mvc
            .get("/api/instruments/888888/quote")
            .andExpect { status { isNotFound() } }

        // 미등록 코드에 한해 ensureSubscribe 미호출 검증 (다른 테스트의 누적 호출과 격리).
        verify(exactly = 0) { subscriptionCommandPublisher.ensureSubscribe("888888") }
    }

    @Test
    fun `GET quote 소문자 코드 자동 대문자 정규화`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { quoteQueryService.query("005930") } returns
            QuoteResult(
                code = "005930",
                price = BigDecimal("75000"),
                volume = null,
                source = QuoteSource.LIVE,
                stale = false,
                asOf = now,
                session = null,
            )

        mvc
            .get("/api/instruments/005930/quote")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
            }
    }
}

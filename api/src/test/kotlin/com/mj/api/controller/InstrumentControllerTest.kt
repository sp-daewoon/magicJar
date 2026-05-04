package com.mj.api.controller

import com.mj.api.application.instrument.InstrumentMasterSyncApiService
import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.instrument.SyncTriggerResult
import com.mj.api.application.market.CandleCacheService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.application.market.CandleQueryService.QueryResult
import com.mj.api.application.market.InstrumentDetailService
import com.mj.api.application.market.InvestorTrendService
import com.mj.api.application.market.OrderbookService
import com.mj.api.application.market.RecentTradesService
import com.mj.api.config.WebSecurityConfig
import com.mj.api.messaging.producer.MarketCandleBackfillCommandPublisher
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentDetail
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
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime

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

        @Bean
        fun candleCacheService(): CandleCacheService = mockk()

        @Bean
        fun instrumentMasterSyncApiService(): InstrumentMasterSyncApiService = mockk()

        @Bean
        fun instrumentDetailService(): InstrumentDetailService = mockk()

        @Bean
        fun orderbookService(): OrderbookService = mockk()

        @Bean
        fun recentTradesService(): RecentTradesService = mockk()

        @Bean
        fun investorTrendService(): InvestorTrendService = mockk()

        @Bean
        fun marketCandleBackfillCommandPublisher(): MarketCandleBackfillCommandPublisher = mockk(relaxed = true)
    }

    @Autowired
    lateinit var mvc: MockMvc

    @Autowired
    lateinit var instrumentService: InstrumentService

    @Autowired
    lateinit var instrumentMasterSyncApiService: InstrumentMasterSyncApiService

    @Autowired
    lateinit var candleQueryService: CandleQueryService

    @Autowired
    lateinit var candleCacheService: CandleCacheService

    @Autowired
    lateinit var instrumentDetailService: InstrumentDetailService

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
        // v2: queryByString 경유 (interval=DAY) — QueryResult 반환
        every { candleQueryService.queryByString("005930", "DAY", null, null, 200) } returns
            QueryResult(
                candles =
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
                    ),
                hasMore = false,
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
                header { string("X-Has-More", "false") }
                header { string("X-Total-Count", "1") }
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
        // v2: M1은 queryByString 경유 — QueryResult 반환
        every { candleQueryService.queryByString("005930", "M1", null, null, 200) } returns
            QueryResult(
                candles =
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
                    ),
                hasMore = false,
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
    fun `GET api instruments code candles before mode returns 200 with candles`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        // v2: queryBeforeByString 경유
        every {
            candleQueryService.queryBeforeByString(
                "005930",
                "DAY",
                Instant.parse("2026-04-30T15:00:00Z"),
                200,
            )
        } returns
            listOf(
                Candle(
                    code = "005930",
                    interval = Interval.D1,
                    openTime = Instant.parse("2026-04-29T00:00:00Z"),
                    open = BigDecimal("59000"),
                    high = BigDecimal("59500"),
                    low = BigDecimal("58500"),
                    close = BigDecimal("59200"),
                    volume = 10_000_000L,
                    tradeCount = 80_000L,
                ),
            )

        mvc
            .get("/api/instruments/005930/candles?before=2026-04-30T15:00:00Z&limit=200")
            .andExpect {
                status { isOk() }
                jsonPath("$[0].time") { value("2026-04-29") }
                jsonPath("$[0].close") { value(59200) }
            }
    }

    @Test
    fun `GET api instruments code candles limit over 1000 returns 400`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

        mvc
            .get("/api/instruments/005930/candles?limit=1001")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET api instruments code candles limit zero returns 400`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

        mvc
            .get("/api/instruments/005930/candles?limit=0")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `GET api instruments code candles accepts DAY alias and empty result`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { candleQueryService.queryByString("005930", "DAY", null, null, 200) } returns
            QueryResult(candles = emptyList(), hasMore = false)

        mvc
            .get("/api/instruments/005930/candles?interval=DAY")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(0) }
                header { string("X-Has-More", "false") }
                header { string("X-Total-Count", "0") }
            }
    }

    // ── v2 분봉 합성 검증 ─────────────────────────────────────────────────────

    @Test
    fun `GET api instruments code candles interval M5 returns aggregated candles from queryByString`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        val aggregatedCandle =
            Candle(
                code = "005930",
                interval = Interval.M1, // 합성 봉은 M1 interval로 반환 (스토리지 타입 재사용)
                openTime = Instant.parse("2026-04-30T00:00:00Z"),
                open = BigDecimal("60000"),
                high = BigDecimal("60600"),
                low = BigDecimal("59400"),
                close = BigDecimal("60300"),
                volume = 61_000L,
                tradeCount = 500L,
            )
        every { candleQueryService.queryByString("005930", "M5", null, null, 200) } returns
            QueryResult(candles = listOf(aggregatedCandle), hasMore = false)

        mvc
            .get("/api/instruments/005930/candles?interval=M5")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].open") { value(60000) }
                jsonPath("$[0].high") { value(60600) }
                jsonPath("$[0].low") { value(59400) }
                jsonPath("$[0].close") { value(60300) }
                jsonPath("$[0].volume") { value(61_000) }
            }
    }

    @Test
    fun `GET api instruments code candles interval M30 returns aggregated candles from queryByString`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        val aggregatedCandle =
            Candle(
                code = "005930",
                interval = Interval.M1,
                openTime = Instant.parse("2026-04-30T00:00:00Z"),
                open = BigDecimal("59800"),
                high = BigDecimal("61000"),
                low = BigDecimal("59200"),
                close = BigDecimal("60500"),
                volume = 360_000L,
                tradeCount = 3_000L,
            )
        every { candleQueryService.queryByString("005930", "M30", null, null, 200) } returns
            QueryResult(candles = listOf(aggregatedCandle), hasMore = false)

        mvc
            .get("/api/instruments/005930/candles?interval=M30")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].open") { value(59800) }
                jsonPath("$[0].close") { value(60500) }
                jsonPath("$[0].volume") { value(360_000) }
            }
    }

    @Test
    fun `GET api instruments code candles interval 90m returns 200`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        val aggregatedCandle =
            Candle(
                code = "005930",
                interval = Interval.M1,
                openTime = Instant.parse("2026-04-30T00:00:00Z"),
                open = BigDecimal("60000"),
                high = BigDecimal("61000"),
                low = BigDecimal("59000"),
                close = BigDecimal("60800"),
                volume = 540_000L,
                tradeCount = 4_500L,
            )
        every { candleQueryService.queryByString("005930", "90m", null, null, 200) } returns
            QueryResult(candles = listOf(aggregatedCandle), hasMore = false)

        mvc
            .get("/api/instruments/005930/candles?interval=90m")
            .andExpect {
                status { isOk() }
                jsonPath("$.length()") { value(1) }
                jsonPath("$[0].volume") { value(540_000) }
            }
    }

    // ── cursor 페이징 / X-Has-More 헤더 테스트 ─────────────────────────────────

    @Test
    fun `GET candles returns X-Has-More true when result equals limit`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        // limit=1 + 1개 반환 → hasMore=true (다음 페이지 존재 추정)
        every { candleQueryService.queryByString("005930", "DAY", null, null, 1) } returns
            QueryResult(
                candles =
                    listOf(
                        Candle(
                            code = "005930",
                            interval = Interval.D1,
                            openTime = Instant.parse("2026-04-30T00:00:00Z"),
                            open = BigDecimal("60000"),
                            high = BigDecimal("60500"),
                            low = BigDecimal("59500"),
                            close = BigDecimal("60200"),
                            volume = 1_000L,
                            tradeCount = 100L,
                        ),
                    ),
                hasMore = true,
            )

        mvc
            .get("/api/instruments/005930/candles?interval=DAY&limit=1")
            .andExpect {
                status { isOk() }
                header { string("X-Has-More", "true") }
                header { string("X-Total-Count", "1") }
                jsonPath("$.length()") { value(1) }
            }
    }

    @Test
    fun `GET candles returns X-Has-More false when result is less than limit`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        // 데이터 1개, limit=200 → hasMore=false
        every { candleQueryService.queryByString("005930", "DAY", null, null, 200) } returns
            QueryResult(
                candles =
                    listOf(
                        Candle(
                            code = "005930",
                            interval = Interval.D1,
                            openTime = Instant.parse("2026-04-30T00:00:00Z"),
                            open = BigDecimal("60000"),
                            high = BigDecimal("60500"),
                            low = BigDecimal("59500"),
                            close = BigDecimal("60200"),
                            volume = 1_000L,
                            tradeCount = 100L,
                        ),
                    ),
                hasMore = false,
            )

        mvc
            .get("/api/instruments/005930/candles")
            .andExpect {
                status { isOk() }
                header { string("X-Has-More", "false") }
                header { string("X-Total-Count", "1") }
            }
    }

    @Test
    fun `GET candles limit 1 valid request`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { candleQueryService.queryByString("005930", "DAY", null, null, 1) } returns
            QueryResult(candles = emptyList(), hasMore = false)

        mvc
            .get("/api/instruments/005930/candles?limit=1")
            .andExpect {
                status { isOk() }
                header { string("X-Has-More", "false") }
            }
    }

    @Test
    fun `GET candles limit 1000 valid request`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { candleQueryService.queryByString("005930", "DAY", null, null, 1000) } returns
            QueryResult(candles = emptyList(), hasMore = false)

        mvc
            .get("/api/instruments/005930/candles?limit=1000")
            .andExpect {
                status { isOk() }
            }
    }

    @Test
    fun `GET candles before mode returns X-Has-More false when less than limit`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        // before 모드: 1개 반환, limit=200 → hasMore=false
        every {
            candleQueryService.queryBeforeByString(
                "005930",
                "DAY",
                Instant.parse("2026-04-30T15:00:00Z"),
                200,
            )
        } returns
            listOf(
                Candle(
                    code = "005930",
                    interval = Interval.D1,
                    openTime = Instant.parse("2026-04-29T00:00:00Z"),
                    open = BigDecimal("59000"),
                    high = BigDecimal("59500"),
                    low = BigDecimal("58500"),
                    close = BigDecimal("59200"),
                    volume = 10_000_000L,
                    tradeCount = 80_000L,
                ),
            )

        mvc
            .get("/api/instruments/005930/candles?before=2026-04-30T15:00:00Z&limit=200")
            .andExpect {
                status { isOk() }
                header { string("X-Has-More", "false") }
                header { string("X-Total-Count", "1") }
                jsonPath("$[0].time") { value("2026-04-29") }
            }
    }

    @Test
    fun `GET candles negative limit returns 400`() {
        every { instrumentService.get("005930") } returns samsungInstrument()

        mvc
            .get("/api/instruments/005930/candles?limit=-1")
            .andExpect {
                status { isBadRequest() }
            }
    }

    @Test
    fun `POST api instruments master sync returns 202 with runId`() {
        every { instrumentMasterSyncApiService.triggerSync(null) } returns
            SyncTriggerResult(
                runId = "test-run-id",
                status = "STARTED",
                scheduledAt = OffsetDateTime.parse("2026-05-02T12:00:00+09:00"),
            )

        mvc
            .post("/api/instruments/master/sync") {
                contentType = org.springframework.http.MediaType.APPLICATION_JSON
                content = "{}"
            }.andExpect {
                status { isAccepted() }
                jsonPath("$.runId") { value("test-run-id") }
                jsonPath("$.status") { value("STARTED") }
            }
    }

    @Test
    fun `GET api instruments master sync runId returns 404 when batch app unavailable`() {
        every { instrumentMasterSyncApiService.syncStatus("unknown-run") } returns null

        mvc
            .get("/api/instruments/master/sync/unknown-run")
            .andExpect {
                status { isNotFound() }
            }
    }

    // ── T20-T5: /detail 엔드포인트 테스트 ────────────────────────────────────

    @Test
    fun `GET api instruments code detail returns 200 with detail response`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { instrumentDetailService.getDetail("005930", "삼성전자") } returns
            InstrumentDetailService.DetailResult(
                detail =
                    InstrumentDetail(
                        code = "005930",
                        name = "삼성전자",
                        market = "KOSPI",
                        sector = "반도체",
                        marketCap = java.math.BigDecimal("400000000000000"),
                        per = java.math.BigDecimal("12.34"),
                        pbr = java.math.BigDecimal("1.23"),
                        eps = java.math.BigDecimal("5765"),
                    ),
                warnings = emptyList(),
            )

        mvc
            .get("/api/instruments/005930/detail")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.name") { value("삼성전자") }
                jsonPath("$.market") { value("KOSPI") }
                jsonPath("$.per") { value(12.34) }
            }
    }

    @Test
    fun `GET api instruments code detail returns 404 for unknown code`() {
        every { instrumentService.get("999999") } returns null

        mvc
            .get("/api/instruments/999999/detail")
            .andExpect {
                status { isNotFound() }
            }
    }

    @Test
    fun `GET api instruments code detail returns 200 with null fields when KIS key not set`() {
        every { instrumentService.get("005930") } returns samsungInstrument()
        every { instrumentDetailService.getDetail("005930", "삼성전자") } returns
            InstrumentDetailService.DetailResult(
                detail = InstrumentDetail(code = "005930", name = "삼성전자"),
                warnings = listOf("KIS_KEY_NOT_SET", "DART_KEY_NOT_SET"),
            )

        mvc
            .get("/api/instruments/005930/detail")
            .andExpect {
                status { isOk() }
                jsonPath("$.code") { value("005930") }
                jsonPath("$.name") { value("삼성전자") }
                // KIS 키 미설정 → 필드 null (non_null 직렬화 정책으로 응답에서 제외됨)
                jsonPath("$.market") { doesNotExist() }
                jsonPath("$.per") { doesNotExist() }
            }
    }
}

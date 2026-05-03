package com.mj.api.mcp

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mj.api.application.instrument.InstrumentService
import com.mj.api.application.market.CandleQueryService
import com.mj.api.application.market.QuoteQueryService
import com.mj.api.application.market.QuoteResult
import com.mj.api.application.order.OrderApiService
import com.mj.api.application.screener.ScreenerExecutor
import com.mj.api.config.WebSecurityConfig
import com.mj.api.controller.dto.OrderResponse
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.api.infrastructure.persistence.screener.ScreenMatchJpa
import com.mj.api.infrastructure.persistence.screener.ScreenMatchJpaRepository
import com.mj.api.infrastructure.persistence.screener.ScreenRunJpa
import com.mj.api.infrastructure.persistence.screener.ScreenRunJpaRepository
import com.mj.api.mcp.tool.GetCandlesTool
import com.mj.api.mcp.tool.GetJournalTool
import com.mj.api.mcp.tool.GetQuoteTool
import com.mj.api.mcp.tool.RunScreenerTool
import com.mj.api.mcp.tool.SearchInstrumentsTool
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Market
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.market.cache.port.QuoteSource
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
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
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.util.UUID

/**
 * MCP server endpoint integration tests (T17 / spec §2 §3 §4).
 *
 * 5 핵심 시나리오 + 인증·rate limit·forbidden 보강.
 *  S1. initialize 핸드셰이크
 *  S2. tools/list — 5 tool 카탈로그 반환
 *  S3. tools/call get_candles — 정상 응답
 *  S4. tools/call search_instruments — 정상 응답
 *  S5. tools/call run_screener — DSL 파싱·매칭 응답
 *  보강: S6 tools/call get_quote · S7 get_journal · S8 unknown method · S9 invalid params
 */
@WebMvcTest(
    controllers = [McpServerController::class],
    excludeAutoConfiguration = [
        DataSourceAutoConfiguration::class,
        HibernateJpaAutoConfiguration::class,
        JpaRepositoriesAutoConfiguration::class,
        FlywayAutoConfiguration::class,
    ],
)
@Import(
    WebSecurityConfig::class,
    McpDispatcher::class,
    McpToolRegistry::class,
    McpAuth::class,
    McpRateLimiter::class,
    GetCandlesTool::class,
    GetQuoteTool::class,
    SearchInstrumentsTool::class,
    RunScreenerTool::class,
    GetJournalTool::class,
)
class McpServerControllerTest {
    @TestConfiguration
    class MockConfig {
        @Bean fun candleQueryService(): CandleQueryService = mockk()

        @Bean fun quoteQueryService(): QuoteQueryService = mockk()

        @Bean fun instrumentService(): InstrumentService = mockk()

        @Bean fun screenerExecutor(): ScreenerExecutor = mockk()

        @Bean fun strategyDslParser(): StrategyDslParser = mockk(relaxed = true)

        @Bean fun screenRunJpaRepository(): ScreenRunJpaRepository = mockk()

        @Bean fun screenMatchJpaRepository(): ScreenMatchJpaRepository = mockk()

        @Bean fun orderApiService(): OrderApiService = mockk()

        @Bean fun mcpAuditLogger(): McpAuditLogger = mockk(relaxed = true)
    }

    @Autowired lateinit var mvc: MockMvc

    @Autowired lateinit var candleQueryService: CandleQueryService

    @Autowired lateinit var quoteQueryService: QuoteQueryService

    @Autowired lateinit var instrumentService: InstrumentService

    @Autowired lateinit var screenerExecutor: ScreenerExecutor

    @Autowired lateinit var screenRunJpaRepository: ScreenRunJpaRepository

    @Autowired lateinit var screenMatchJpaRepository: ScreenMatchJpaRepository

    @Autowired lateinit var orderApiService: OrderApiService

    private val mapper: ObjectMapper = jacksonObjectMapper()

    // ────────────────────────────────────────────
    //  S1. initialize
    // ────────────────────────────────────────────

    @Test
    fun `S1 initialize handshake returns server info and capabilities`() {
        val req = """{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}"""
        val resp = postJsonRpc(req)
        assertEquals(1, resp.path("id").asInt())
        assertTrue(resp.path("error").isMissingNode || resp.path("error").isNull)
        val result = resp.path("result")
        assertEquals("2025-03-26", result.path("protocolVersion").asText())
        assertEquals("magicjar-mcp", result.path("serverInfo").path("name").asText())
        assertTrue(result.path("capabilities").has("tools"))
    }

    // S1 sends id=1 but assert expects id=2 — fix via reading echo correctly
    private fun postJsonRpc(body: String) =
        mvc
            .post("/mcp/v1/jsonrpc") {
                contentType = MediaType.APPLICATION_JSON
                content = body
            }.andReturn()
            .response
            .let { mapper.readTree(it.contentAsByteArray) }

    // ────────────────────────────────────────────
    //  S2. tools/list
    // ────────────────────────────────────────────

    @Test
    fun `S2 tools list returns 5 read-only tools`() {
        val resp = postJsonRpc("""{"jsonrpc":"2.0","id":"abc","method":"tools/list"}""")
        assertEquals("abc", resp.path("id").asText())
        val tools = resp.path("result").path("tools")
        assertTrue(tools.isArray)
        val names = tools.map { it.path("name").asText() }.toSet()
        assertEquals(
            setOf("get_candles", "get_quote", "search_instruments", "run_screener", "get_journal"),
            names,
        )
        // 모두 read-only (ADR 0045)
        tools.forEach { assertTrue(it.path("readOnly").asBoolean(), "tool ${it.path("name").asText()} must be readOnly") }
    }

    // ────────────────────────────────────────────
    //  S3. tools/call get_candles
    // ────────────────────────────────────────────

    @Test
    fun `S3 tools call get_candles returns OHLCV array`() {
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

        val req =
            """
            {
              "jsonrpc":"2.0","id":42,"method":"tools/call",
              "params":{"name":"get_candles","arguments":{"code":"005930","interval":"D1"}}
            }
            """.trimIndent()

        val resp = postJsonRpc(req)
        assertEquals(42, resp.path("id").asInt())
        val structured = resp.path("result").path("structuredContent")
        assertEquals("005930", structured.path("code").asText())
        assertEquals("D1", structured.path("interval").asText())
        assertEquals(1, structured.path("count").asInt())
        val firstCandle = structured.path("candles").get(0)
        assertEquals("60000", firstCandle.path("open").asText())
        assertEquals(12_345_678L, firstCandle.path("volume").asLong())
    }

    // ────────────────────────────────────────────
    //  S4. tools/call search_instruments
    // ────────────────────────────────────────────

    @Test
    fun `S4 tools call search_instruments returns code name market`() {
        every { instrumentService.search("삼성", 5) } returns
            listOf(
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
                ),
            )

        val req =
            """
            {"jsonrpc":"2.0","id":7,"method":"tools/call",
             "params":{"name":"search_instruments","arguments":{"query":"삼성","limit":5}}}
            """.trimIndent()
        val resp = postJsonRpc(req)
        val structured = resp.path("result").path("structuredContent")
        assertEquals(1, structured.path("count").asInt())
        val first = structured.path("instruments").get(0)
        assertEquals("005930", first.path("code").asText())
        assertEquals("삼성전자", first.path("name").asText())
        assertEquals("KOSPI", first.path("market").asText())
    }

    // ────────────────────────────────────────────
    //  S5. tools/call run_screener
    // ────────────────────────────────────────────

    @Test
    fun `S5 tools call run_screener returns runId and matches`() {
        // dslParser는 mockk(relaxed=true) — 어떤 yaml이든 통과
        every { screenerExecutor.execute(any(), any(), any()) } returns "run-uuid-1"
        val runJpa =
            ScreenRunJpa(
                id = 1L,
                runId = "run-uuid-1",
                strategyYaml = "fake",
                universeType = "CUSTOM",
                universeDetail = null,
                asOfDate = LocalDate.of(2026, 5, 3),
                resultJson = null,
                totalScanned = 1,
                matchCount = 1,
                skipCount = 0,
                status = "COMPLETED",
                errorMessage = null,
                userId = null,
                createdAt = Instant.now(),
                finishedAt = Instant.now(),
            )
        every { screenRunJpaRepository.findByRunId("run-uuid-1") } returns runJpa
        every { screenMatchJpaRepository.findByScreenRunId(1L) } returns
            listOf(
                ScreenMatchJpa(
                    id = 11L,
                    screenRun = runJpa,
                    code = "005930",
                    name = "삼성전자",
                    entryReason = "[]",
                    matchedAt = Instant.parse("2026-05-03T00:00:00Z"),
                ),
            )

        val req =
            """
            {"jsonrpc":"2.0","id":99,"method":"tools/call",
             "params":{"name":"run_screener","arguments":{
                "universe":"CUSTOM",
                "customCodes":["005930"],
                "dslYaml":"any: yaml",
                "asOfDate":"2026-05-03"
             }}}
            """.trimIndent()
        val resp = postJsonRpc(req)
        val structured = resp.path("result").path("structuredContent")
        assertEquals("run-uuid-1", structured.path("runId").asText())
        assertEquals(1, structured.path("matchCount").asInt())
        val match0 = structured.path("matches").get(0)
        assertEquals("005930", match0.path("code").asText())
        assertEquals("삼성전자", match0.path("name").asText())
    }

    // ────────────────────────────────────────────
    //  Bonus: get_quote
    // ────────────────────────────────────────────

    @Test
    fun `S6 tools call get_quote returns 3-tier source label`() {
        every { quoteQueryService.query("005930") } returns
            QuoteResult(
                code = "005930",
                price = BigDecimal("60100"),
                volume = 100L,
                source = QuoteSource.LIVE,
                stale = false,
                asOf = Instant.parse("2026-05-03T00:30:00Z"),
                session =
                    MarketSession(
                        state = MarketSessionState.Open,
                        kstAt = OffsetDateTime.parse("2026-05-03T09:30:00+09:00"),
                        lastBusinessDate = LocalDate.of(2026, 5, 3),
                        nextOpenAt = null,
                    ),
            )

        val req =
            """
            {"jsonrpc":"2.0","id":"q1","method":"tools/call",
             "params":{"name":"get_quote","arguments":{"code":"005930"}}}
            """.trimIndent()
        val resp = postJsonRpc(req)
        val s = resp.path("result").path("structuredContent")
        assertEquals("LIVE", s.path("source").asText())
        assertEquals(false, s.path("stale").asBoolean())
        assertEquals("OPEN", s.path("session").asText())
    }

    // ────────────────────────────────────────────
    //  Bonus: get_journal — read-only audit
    // ────────────────────────────────────────────

    @Test
    fun `S7 tools call get_journal returns user orders`() {
        val now = OffsetDateTime.parse("2026-05-03T09:30:00+09:00")
        every { orderApiService.listOrders(any()) } returns
            listOf(
                OrderResponse(
                    orderId = UUID.randomUUID(),
                    kisOrderNo = "0001",
                    krxFwdgOrdOrgno = "01",
                    accountNo = "12345678-01",
                    kisMode = "MOCK_KIS",
                    code = "005930",
                    side = Side.BUY,
                    qty = 1,
                    price = BigDecimal("60000"),
                    orderType = OrderType.LIMIT,
                    status = OrderStatus.FILLED,
                    filledQty = 1,
                    avgFillPrice = BigDecimal("60000"),
                    rejectReason = null,
                    rejectReasonCode = null,
                    rejectDetail = null,
                    rejectSource = null,
                    createdAt = now,
                    updatedAt = now,
                ),
            )

        val req =
            """
            {"jsonrpc":"2.0","id":"j1","method":"tools/call",
             "params":{"name":"get_journal","arguments":{}}}
            """.trimIndent()
        val resp = postJsonRpc(req)
        val s = resp.path("result").path("structuredContent")
        assertEquals(1, s.path("count").asInt())
        assertEquals(
            "005930",
            s
                .path("orders")
                .get(0)
                .path("code")
                .asText(),
        )
        assertEquals(
            "BUY",
            s
                .path("orders")
                .get(0)
                .path("side")
                .asText(),
        )
    }

    // ────────────────────────────────────────────
    //  보강: 미지원 method · invalid params
    // ────────────────────────────────────────────

    @Test
    fun `S8 unknown method returns -32601 METHOD_NOT_FOUND`() {
        val req = """{"jsonrpc":"2.0","id":"x","method":"prompts/list"}"""
        val resp = postJsonRpc(req)
        assertEquals(-32601, resp.path("error").path("code").asInt())
    }

    @Test
    fun `S9 invalid params returns -32602`() {
        // code 5자리 — pattern 위반
        val req =
            """
            {"jsonrpc":"2.0","id":1,"method":"tools/call",
             "params":{"name":"get_candles","arguments":{"code":"00593"}}}
            """.trimIndent()
        val resp = postJsonRpc(req)
        assertEquals(-32602, resp.path("error").path("code").asInt())
    }

    @Test
    fun `S10 unknown tool returns -32001 TOOL_NOT_FOUND`() {
        val req =
            """
            {"jsonrpc":"2.0","id":2,"method":"tools/call",
             "params":{"name":"place_order","arguments":{}}}
            """.trimIndent()
        val resp = postJsonRpc(req)
        // place_order 등 매매 트리거 tool은 등록 자체 안 함 → TOOL_NOT_FOUND.
        assertEquals(-32001, resp.path("error").path("code").asInt())
    }
}

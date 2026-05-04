package com.mj.batch.infrastructure.kis

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.verify
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.mj.domain.common.Interval
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate

/**
 * RealKisRestAdapterIT — WireMock을 사용해 키 없이 KIS REST API 동작을 검증한다.
 *
 * 시나리오:
 *  1. 토큰 발급 성공 → token() 반환값 확인
 *  2. fetchCandles() — 일봉 2건 정상 응답 → Candle 도메인 변환 확인
 *  3. fetchQuote() — 현재가 정상 응답 → Quote 도메인 변환 확인
 *  4. 401 응답 후 토큰 재발급 + 재시도 성공 (fetchCandles 기준)
 *  5. rt_cd != "0" 에러 응답 → KisApiException 발생 확인
 */
class RealKisRestAdapterIT {
    private lateinit var wireMock: WireMockServer
    private lateinit var adapter: RealKisRestAdapter

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()

        // WireMock static DSL을 이 서버 인스턴스로 바인딩
        com.github.tomakehurst.wiremock.client.WireMock
            .configureFor("localhost", wireMock.port())

        val props =
            KisRestProperties(
                mode = "MOCK",
                baseUrl = "http://localhost:${wireMock.port()}",
                appKey = "test-app-key",
                appSecret = "test-app-secret",
            )
        adapter = RealKisRestAdapter(props)
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    // -------------------------------------------------------------------------
    // 시나리오 1: 토큰 발급 성공
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 1 - token is issued and returned`() {
        stubTokenOk("scenario1-token")

        val token = adapter.token()

        assertEquals("scenario1-token", token)
        // 두 번 호출해도 캐시에서 동일 토큰 반환
        assertEquals("scenario1-token", adapter.token())
    }

    // -------------------------------------------------------------------------
    // 시나리오 2: fetchCandles 일봉 2건
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 2 - fetchCandles returns 2 daily candles`() {
        stubTokenOk("candle-token")
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"))
                .withQueryParam("FID_INPUT_ISCD", equalTo("005930"))
                .withQueryParam("FID_PERIOD_DIV_CODE", equalTo("D"))
                .withHeader("tr_id", equalTo("FHKST03010100"))
                .withHeader("appkey", equalTo("test-app-key"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(CANDLE_RESPONSE_2ROWS),
                ),
        )

        val candles = adapter.fetchCandles("005930", Interval.D1, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3))

        assertEquals(2, candles.size)

        // 페이징 v2: 결과는 openTime ASC 정렬 — KIS 응답(desc)이 아닌 우리 system 컨벤션.
        val first = candles[0] // 2024-01-02 (older)
        assertEquals("005930", first.code)
        assertEquals(Interval.D1, first.interval)
        assertEquals(BigDecimal("73200"), first.open)
        assertEquals(BigDecimal("74000"), first.high)
        assertEquals(BigDecimal("73100"), first.low)
        assertEquals(BigDecimal("73800"), first.close)
        assertEquals(13500000L, first.volume)

        val second = candles[1] // 2024-01-03 (newer)
        assertEquals(BigDecimal("73000"), second.open)
        assertEquals(BigDecimal("73500"), second.high)
        assertEquals(BigDecimal("72800"), second.low)
        assertEquals(BigDecimal("73200"), second.close)
        assertEquals(15000000L, second.volume)
    }

    // -------------------------------------------------------------------------
    // 시나리오 3: fetchQuote 현재가
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 3 - fetchQuote returns current price`() {
        stubTokenOk("quote-token")
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
                .withQueryParam("FID_INPUT_ISCD", equalTo("005930"))
                .withHeader("tr_id", equalTo("FHKST01010100"))
                .withHeader("appkey", equalTo("test-app-key"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(QUOTE_RESPONSE),
                ),
        )

        val quote = adapter.fetchQuote("005930")

        assertEquals("005930", quote.code)
        assertEquals(BigDecimal("73800"), quote.price)
        assertEquals(28500000L, quote.volume)
        assertNotNull(quote.at)
    }

    // -------------------------------------------------------------------------
    // 시나리오 4: 401 응답 → 토큰 재발급 + 재시도 성공
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 4 - 401 triggers token refresh and retry succeeds`() {
        // 첫 번째 토큰 발급
        stubFor(
            post(urlPathEqualTo("/oauth2/tokenP"))
                .inScenario("token-refresh")
                .whenScenarioStateIs("Started")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token":"first-token"}"""),
                ).willSetStateTo("first-token-issued"),
        )

        // 첫 번째 일봉 요청: 401 반환
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"))
                .inScenario("token-refresh")
                .whenScenarioStateIs("first-token-issued")
                .willReturn(
                    aResponse()
                        .withStatus(401)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"rt_cd":"1","msg_cd":"EGW00401","msg1":"Unauthorized"}"""),
                ).willSetStateTo("401-received"),
        )

        // 두 번째 토큰 재발급
        stubFor(
            post(urlPathEqualTo("/oauth2/tokenP"))
                .inScenario("token-refresh")
                .whenScenarioStateIs("401-received")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token":"refreshed-token"}"""),
                ).willSetStateTo("token-refreshed"),
        )

        // 재시도 일봉 요청: 성공
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"))
                .inScenario("token-refresh")
                .whenScenarioStateIs("token-refreshed")
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(CANDLE_RESPONSE_2ROWS),
                ),
        )

        val candles = adapter.fetchCandles("005930", Interval.D1, LocalDate.of(2024, 1, 2), LocalDate.of(2024, 1, 3))

        assertEquals(2, candles.size)
        // 재발급 후 토큰이 갱신되었는지 확인
        assertEquals("refreshed-token", adapter.token())
    }

    // -------------------------------------------------------------------------
    // 시나리오 5: rt_cd != "0" 에러 응답
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 5 - rt_cd error response throws KisApiException`() {
        stubTokenOk("error-token")
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-price"))
                .withQueryParam("FID_INPUT_ISCD", equalTo("INVALID"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "rt_cd": "1",
                              "msg_cd": "EGW00123",
                              "msg1": "종목코드가 올바르지 않습니다.",
                              "output": {}
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val ex =
            assertThrows(KisApiException::class.java) {
                adapter.fetchQuote("INVALID")
            }

        assertTrue(ex.message!!.contains("rt_cd=1"), "Exception message should contain rt_cd=1, got: ${ex.message}")
        assertEquals("EGW00123", ex.msgCd)
        assertEquals("1", ex.rtCd)
    }

    // -------------------------------------------------------------------------
    // 시나리오 6: 페이징 — 100봉 응답 시 두 번째 페이지 자동 호출
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 6 - paging fetches next page when first page returns KIS_PAGE_LIMIT rows`() {
        stubTokenOk("paging-token")

        // 페이지 1: to=2024-04-30 → 100봉 응답 (역순 2024-04-30 ~ 2023-12-08)
        val page1Json = buildCandleResponseJson(endDate = LocalDate.of(2024, 4, 30), count = 100)
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"))
                .withQueryParam("FID_INPUT_ISCD", equalTo("005930"))
                .withQueryParam("FID_INPUT_DATE_2", equalTo("20240430"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(page1Json),
                ),
        )

        // 페이지 2: page1 oldest = 2024-04-30 - 99day = 2024-01-22 → cursor = 2024-01-21
        // 30봉 응답 (< 100, 종료). endDate 2024-01-21부터 역순 30일 = ~2023-12-23.
        val page2Json = buildCandleResponseJson(endDate = LocalDate.of(2024, 1, 21), count = 30)
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"))
                .withQueryParam("FID_INPUT_ISCD", equalTo("005930"))
                .withQueryParam("FID_INPUT_DATE_2", equalTo("20240121"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(page2Json),
                ),
        )

        val candles =
            adapter.fetchCandles(
                code = "005930",
                interval = Interval.D1,
                from = LocalDate.of(2023, 11, 1),
                to = LocalDate.of(2024, 4, 30),
            )

        // 100 + 30 = 130 (중복 없음)
        assertEquals(130, candles.size)
        // 정렬 ASC 확인
        assertTrue(candles.first().openTime.isBefore(candles.last().openTime))

        // KIS API 정확히 2회 호출됐는지 검증
        verify(2, getRequestedFor(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")))
    }

    @Test
    fun `scenario 6b - first page returns less than KIS_PAGE_LIMIT — single call only`() {
        stubTokenOk("single-page-token")

        // 50봉 (< 100) — 페이징 호출 안 됨
        val pageJson = buildCandleResponseJson(endDate = LocalDate.of(2024, 4, 30), count = 50)
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice"))
                .withQueryParam("FID_INPUT_ISCD", equalTo("005930"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(pageJson),
                ),
        )

        val candles =
            adapter.fetchCandles(
                code = "005930",
                interval = Interval.D1,
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 4, 30),
            )

        assertEquals(50, candles.size)
        // KIS API 1회만 호출
        verify(1, getRequestedFor(urlPathEqualTo("/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice")))
    }

    // -------------------------------------------------------------------------
    // 공통 헬퍼
    // -------------------------------------------------------------------------

    /**
     * KIS inquire-daily-itemchartprice 응답 모의 — output2에 endDate부터 1일씩 역순으로 count건.
     * 영업일 단순화 (주말 무시) — 페이징 로직 검증 목적이라 KRX 영업일 정확도 불필요.
     */
    private fun buildCandleResponseJson(
        endDate: LocalDate,
        count: Int,
    ): String {
        val rows =
            (0 until count).joinToString(",\n") { i ->
                val date = endDate.minusDays(i.toLong())
                val ymd = date.toString().replace("-", "")
                """
                {
                  "stck_bsop_date": "$ymd",
                  "stck_oprc": "73000",
                  "stck_hgpr": "73500",
                  "stck_lwpr": "72800",
                  "stck_clpr": "73200",
                  "acml_vol": "15000000",
                  "acml_tr_pbmn": "1095000000000"
                }
                """.trimIndent()
            }
        return """
            {
              "rt_cd": "0",
              "msg_cd": "MCA00000",
              "msg1": "정상처리 되었습니다.",
              "output1": { "stck_prpr": "73800" },
              "output2": [
                $rows
              ]
            }
            """.trimIndent()
    }

    private fun stubTokenOk(token: String) {
        stubFor(
            post(urlPathEqualTo("/oauth2/tokenP"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token":"$token"}"""),
                ),
        )
    }

    // -------------------------------------------------------------------------
    // 응답 픽스처 JSON
    // -------------------------------------------------------------------------

    companion object {
        private val CANDLE_RESPONSE_2ROWS =
            """
            {
              "rt_cd": "0",
              "msg_cd": "MCA00000",
              "msg1": "정상처리 되었습니다.",
              "output1": {
                "stck_prpr": "73800",
                "prdy_vrss": "600"
              },
              "output2": [
                {
                  "stck_bsop_date": "20240103",
                  "stck_oprc": "73000",
                  "stck_hgpr": "73500",
                  "stck_lwpr": "72800",
                  "stck_clpr": "73200",
                  "acml_vol": "15000000",
                  "acml_tr_pbmn": "1095000000000"
                },
                {
                  "stck_bsop_date": "20240102",
                  "stck_oprc": "73200",
                  "stck_hgpr": "74000",
                  "stck_lwpr": "73100",
                  "stck_clpr": "73800",
                  "acml_vol": "13500000",
                  "acml_tr_pbmn": "997000000000"
                }
              ]
            }
            """.trimIndent()

        private val QUOTE_RESPONSE =
            """
            {
              "rt_cd": "0",
              "msg_cd": "MCA00000",
              "msg1": "정상처리 되었습니다.",
              "output": {
                "stck_prpr": "73800",
                "acml_vol": "28500000",
                "prdy_vrss": "600",
                "prdy_vrss_sign": "2",
                "prdy_ctrt": "0.82"
              }
            }
            """.trimIndent()
    }
}

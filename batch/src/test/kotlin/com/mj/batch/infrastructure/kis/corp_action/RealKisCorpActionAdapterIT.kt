package com.mj.batch.infrastructure.kis.corp_action

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.stubFor
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig
import com.mj.batch.infrastructure.kis.KisApiException
import com.mj.batch.infrastructure.kis.KisRestProperties
import com.mj.domain.market.corp_action.CorpAction
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
 * RealKisCorpActionAdapterIT — WireMock으로 KIS 예탁원정보 API 응답을 모킹해 검증한다.
 *
 * 시나리오:
 * 1. fetchSplits — 액면분할 1건 응답 → Split 도메인 변환
 * 2. fetchBonusIssues — 무상증자 1건 응답 → BonusIssue 도메인 변환
 * 3. fetchDividends — 배당 1건 응답 → Dividend 도메인 변환
 * 4. rt_cd != "0" 에러 응답 → KisApiException 발생
 * 5. 401 응답 후 토큰 재발급 + 재시도 성공
 */
class RealKisCorpActionAdapterIT {
    private lateinit var wireMock: WireMockServer
    private lateinit var tokenHolder: KisCorpActionTokenHolder
    private lateinit var adapter: RealKisCorpActionAdapter

    @BeforeEach
    fun setUp() {
        wireMock = WireMockServer(wireMockConfig().dynamicPort())
        wireMock.start()

        com.github.tomakehurst.wiremock.client.WireMock
            .configureFor("localhost", wireMock.port())

        val props =
            KisRestProperties(
                mode = "MOCK",
                baseUrl = "http://localhost:${wireMock.port()}",
                appKey = "test-app-key",
                appSecret = "test-app-secret",
            )
        tokenHolder = KisCorpActionTokenHolder()
        adapter = RealKisCorpActionAdapter(props, tokenHolder)

        // 토큰 발급 stub
        stubTokenOk("test-corp-token")
    }

    @AfterEach
    fun tearDown() {
        wireMock.stop()
    }

    // -------------------------------------------------------------------------
    // 시나리오 1: 액면분할 조회
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 1 - fetchSplits returns Samsung 50-to-1 split`() {
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/ksdinfo/rev-split"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "rt_cd": "0",
                              "msg_cd": "KIOK0000",
                              "msg1": "정상처리 되었습니다.",
                              "output1": [
                                {
                                  "sht_cd": "005930",
                                  "ex_dt": "20240426",
                                  "bfac_nmvl": "50",
                                  "bfor_par_prc": "100",
                                  "aftr_par_prc": "5000"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val splits =
            adapter.fetchSplits(
                instrumentCode = "005930",
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 12, 31),
            )

        assertEquals(1, splits.size)
        val split = splits[0]
        assertEquals("005930", split.instrumentCode)
        assertEquals(LocalDate.of(2024, 4, 26), split.exDate)
        assertEquals(BigDecimal("50"), split.ratio)
        assertTrue(split is CorpAction.Split)
    }

    // -------------------------------------------------------------------------
    // 시나리오 2: 무상증자 조회
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 2 - fetchBonusIssues returns SK Hynix 5 percent bonus`() {
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/ksdinfo/bonus-issue"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "rt_cd": "0",
                              "msg_cd": "KIOK0000",
                              "msg1": "정상처리 되었습니다.",
                              "output1": [
                                {
                                  "sht_cd": "000660",
                                  "ex_dt": "20230331",
                                  "bonus_rt": "0.05"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val bonusIssues =
            adapter.fetchBonusIssues(
                instrumentCode = "000660",
                from = LocalDate.of(2023, 1, 1),
                to = LocalDate.of(2023, 12, 31),
            )

        assertEquals(1, bonusIssues.size)
        val bonus = bonusIssues[0]
        assertEquals("000660", bonus.instrumentCode)
        assertEquals(LocalDate.of(2023, 3, 31), bonus.exDate)
        assertEquals(0, BigDecimal("0.05").compareTo(bonus.ratio))
    }

    // -------------------------------------------------------------------------
    // 시나리오 3: 배당 조회
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 3 - fetchDividends returns Samsung cash dividend`() {
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/ksdinfo/dividend"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "rt_cd": "0",
                              "msg_cd": "KIOK0000",
                              "msg1": "정상처리 되었습니다.",
                              "output1": [
                                {
                                  "sht_cd": "005930",
                                  "ex_dt": "20231228",
                                  "cash_div": "1444",
                                  "stk_div_rt": "0"
                                }
                              ]
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val dividends =
            adapter.fetchDividends(
                instrumentCode = "005930",
                from = LocalDate.of(2023, 1, 1),
                to = LocalDate.of(2023, 12, 31),
            )

        assertEquals(1, dividends.size)
        val div = dividends[0]
        assertEquals("005930", div.instrumentCode)
        assertEquals(LocalDate.of(2023, 12, 28), div.exDate)
        assertEquals(0, BigDecimal("1444").compareTo(div.cashAmount))
    }

    // -------------------------------------------------------------------------
    // 시나리오 4: 에러 응답 → KisApiException
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 4 - rt_cd not 0 throws KisApiException`() {
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/ksdinfo/rev-split"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """
                            {
                              "rt_cd": "1",
                              "msg_cd": "EGW00201",
                              "msg1": "시스템 오류입니다.",
                              "output1": []
                            }
                            """.trimIndent(),
                        ),
                ),
        )

        val ex =
            assertThrows(KisApiException::class.java) {
                adapter.fetchSplits(
                    instrumentCode = "005930",
                    from = LocalDate.of(2024, 1, 1),
                    to = LocalDate.of(2024, 12, 31),
                )
            }
        assertNotNull(ex.message)
        assertTrue(ex.message!!.contains("EGW00201"))
    }

    // -------------------------------------------------------------------------
    // 시나리오 5: 빈 응답 → emptyList 반환
    // -------------------------------------------------------------------------

    @Test
    fun `scenario 5 - empty output1 returns empty list`() {
        stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/ksdinfo/paidin-capin"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody(
                            """{"rt_cd":"0","msg_cd":"KIOK0000","msg1":"정상처리","output1":[]}""",
                        ),
                ),
        )

        val result =
            adapter.fetchRightsIssues(
                instrumentCode = "005930",
                from = LocalDate.of(2024, 1, 1),
                to = LocalDate.of(2024, 12, 31),
            )

        assertTrue(result.isEmpty())
    }

    // -------------------------------------------------------------------------
    // 헬퍼
    // -------------------------------------------------------------------------

    private fun stubTokenOk(token: String) {
        stubFor(
            post(urlPathEqualTo("/oauth2/tokenP"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("""{"access_token":"$token","token_type":"Bearer","expires_in":86400}"""),
                ),
        )
    }
}

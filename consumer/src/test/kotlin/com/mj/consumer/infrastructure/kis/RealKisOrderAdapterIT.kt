package com.mj.consumer.infrastructure.kis

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.aResponse
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.get
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo
import com.mj.domain.common.Order
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import com.mj.domain.user.TradingMode
import com.mj.domain.user.port.KisCredential
import com.mj.domain.user.port.KisCredentialProvider
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

class RealKisOrderAdapterIT {
    private lateinit var wm: WireMockServer
    private lateinit var adapter: RealKisOrderAdapter
    private val userId = "74683223"

    @BeforeEach
    fun setUp() {
        wm = WireMockServer(0).also { it.start() }
        // mock·real 모두 같은 wm을 가리키게 두면 충분 (테스트는 mode 분기 로직만 검증).
        val sharedSession = KisHttpSession(baseUrl = "http://localhost:${wm.port()}")
        // 토큰 발급 stub — 모든 호출 통과
        wm.stubFor(
            post(urlEqualTo("/oauth2/tokenP")).willReturn(
                okJson("""{"access_token":"TOK","token_type":"Bearer","expires_in":86400}"""),
            ),
        )
        val credProvider =
            mockk<KisCredentialProvider>().also {
                every { it.forUser(userId) } returns
                    KisCredential(
                        tradingMode = TradingMode.MOCK_KIS,
                        accountNo = "50185338",
                        accountProductCode = "01",
                        appKey = "AK",
                        appSecret = "AS",
                        htsId = "HTSID",
                    )
            }
        // T2-T7: hashkey endpoint stub
        wm.stubFor(
            post(urlEqualTo("/uapi/hashkey")).willReturn(
                okJson("""{"HASH":"FAKE_HASH_VALUE_FOR_TEST"}"""),
            ),
        )
        val hashKeyProps = KisHashKeyProperties(hashkeyEnabled = true)
        val hashKeyClient = KisHashKeyClient(sharedSession, sharedSession, hashKeyProps)
        adapter = RealKisOrderAdapter(sharedSession, sharedSession, credProvider, hashKeyClient, rawHttpLogging = false)
    }

    @AfterEach
    fun tearDown() {
        wm.stop()
    }

    @Test
    fun `placeOrder demo BUY LIMIT returns ODNO`() {
        wm.stubFor(
            post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withHeader("tr_id", equalTo("VTTC0012U"))
                .withRequestBody(matchingJsonPath("$.PDNO", equalTo("005930")))
                .withRequestBody(matchingJsonPath("$.ORD_DVSN", equalTo("00")))
                .willReturn(
                    okJson(
                        """{"rt_cd":"0","msg_cd":"OK","msg1":"정상",
                              "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000123456","ORD_TMD":"143025"}}""",
                    ),
                ),
        )

        val order =
            Order(
                id = UUID.randomUUID(),
                code = "005930",
                side = Side.BUY,
                qty = 1,
                price = BigDecimal("70000"),
                type = OrderType.LIMIT,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(),
            )
        val result = adapter.placeOrder(userId, order)
        result.kisOrderNo shouldBe "0000123456"
        result.krxFwdgOrdOrgno shouldBe "06010"
        result.orderTime shouldBe "143025"
    }

    @Test
    fun `placeOrder demo SELL MARKET uses VTTC0011U with ord_dvsn=01`() {
        wm.stubFor(
            post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .withHeader("tr_id", equalTo("VTTC0011U"))
                .withRequestBody(matchingJsonPath("$.ORD_DVSN", equalTo("01")))
                .willReturn(
                    okJson(
                        """{"rt_cd":"0","msg_cd":"OK",
                              "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000999999","ORD_TMD":"150000"}}""",
                    ),
                ),
        )
        val order =
            Order(
                id = UUID.randomUUID(),
                code = "005930",
                side = Side.SELL,
                qty = 1,
                price = null,
                type = OrderType.MARKET,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(),
            )
        val result = adapter.placeOrder(userId, order)
        result.kisOrderNo shouldBe "0000999999"
    }

    @Test
    fun `cancelOrder demo uses VTTC0013U with rvse_cncl_dvsn_cd=02`() {
        wm.stubFor(
            post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-rvsecncl"))
                .withHeader("tr_id", equalTo("VTTC0013U"))
                .withRequestBody(matchingJsonPath("$.RVSE_CNCL_DVSN_CD", equalTo("02")))
                .withRequestBody(matchingJsonPath("$.ORGN_ODNO", equalTo("0000123456")))
                .willReturn(
                    okJson(
                        """{"rt_cd":"0","msg_cd":"OK",
                              "output":{"KRX_FWDG_ORD_ORGNO":"06010","ODNO":"0000999998","ORD_TMD":"143130"}}""",
                    ),
                ),
        )
        val res = adapter.cancelOrder(userId, "0000123456", "06010", qtyAll = true, qty = 0)
        res.kisOrderNo shouldBe "0000999998"
    }

    @Test
    fun `fetchBalance returns holdings and account summary`() {
        wm.stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-balance"))
                .withHeader("tr_id", equalTo("VTTC8434R"))
                .willReturn(
                    okJson(
                        """{"rt_cd":"0","msg_cd":"OK",
                              "output1":[
                                {"pdno":"005930","prdt_name":"삼성전자","hldg_qty":"1","ord_psbl_qty":"1",
                                 "pchs_avg_pric":"70000","prpr":"71000","evlu_amt":"71000","evlu_pfls_amt":"1000"}
                              ],
                              "output2":[
                                {"dnca_tot_amt":"500000","nxdy_excc_amt":"500000","prvs_rcdl_excc_amt":"500000",
                                 "tot_evlu_amt":"571000","nass_amt":"571000","evlu_pfls_smtl_amt":"1000"}
                              ],
                              "ctx_area_fk100":"","ctx_area_nk100":""}""",
                    ),
                ),
        )
        val snap = adapter.fetchBalance(userId)
        snap.holdings.size shouldBe 1
        snap.holdings[0].code shouldBe "005930"
        snap.holdings[0].heldQty shouldBe 1
        snap.account.depositAmount shouldBe BigDecimal("500000")
        snap.account.totalEvaluationAmount shouldBe BigDecimal("571000")
    }

    @Test
    fun `fetchExecutions returns lines for today`() {
        wm.stubFor(
            get(urlPathEqualTo("/uapi/domestic-stock/v1/trading/inquire-daily-ccld"))
                .withHeader("tr_id", equalTo("VTTC0081R"))
                .willReturn(
                    okJson(
                        """{"rt_cd":"0","msg_cd":"OK",
                              "output1":[
                                {"ord_dt":"20260502","ord_gno_brno":"06010","odno":"0000123456",
                                 "orgn_odno":"","pdno":"005930","sll_buy_dvsn_cd":"02",
                                 "ord_qty":"1","ord_unpr":"70000","tot_ccld_qty":"1","tot_ccld_amt":"70000",
                                 "cncl_yn":"N","rmn_qty":"0","rjct_qty":"0"}
                              ],
                              "output2":[],
                              "ctx_area_fk100":"","ctx_area_nk100":""}""",
                    ),
                ),
        )
        val lines = adapter.fetchExecutions(userId, LocalDate.parse("2026-05-02"))
        lines.size shouldBe 1
        lines[0].kisOrderNo shouldBe "0000123456"
        lines[0].cumulativeExecutedQty shouldBe 1
        lines[0].side shouldBe Side.BUY
    }

    @Test
    fun `INVALID_ORDER throws KisApiException with category`() {
        wm.stubFor(
            post(urlEqualTo("/uapi/domestic-stock/v1/trading/order-cash"))
                .willReturn(
                    aResponse()
                        .withStatus(200)
                        .withBody("""{"rt_cd":"1","msg_cd":"APBK0067","msg1":"호가 단위 오류"}""")
                        .withHeader("Content-Type", "application/json"),
                ),
        )
        val order =
            Order(
                id = UUID.randomUUID(),
                code = "005930",
                side = Side.BUY,
                qty = 1,
                price = BigDecimal("70001"),
                type = OrderType.LIMIT,
                status = OrderStatus.PENDING,
                createdAt = Instant.now(),
            )
        val ex = assertThrows<KisApiException> { adapter.placeOrder(userId, order) }
        ex.category shouldBe KisErrorCategory.INVALID_ORDER
    }
}

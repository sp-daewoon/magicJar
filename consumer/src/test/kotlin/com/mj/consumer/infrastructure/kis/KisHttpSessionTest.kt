package com.mj.consumer.infrastructure.kis

import com.github.tomakehurst.wiremock.WireMockServer
import com.github.tomakehurst.wiremock.client.WireMock.equalTo
import com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath
import com.github.tomakehurst.wiremock.client.WireMock.okJson
import com.github.tomakehurst.wiremock.client.WireMock.post
import com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor
import com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KisHttpSessionTest {
    private lateinit var wm: WireMockServer
    private lateinit var session: KisHttpSession

    @BeforeEach
    fun setUp() {
        wm = WireMockServer(0).also { it.start() }
        session = KisHttpSession(baseUrl = "http://localhost:${wm.port()}")
    }

    @AfterEach
    fun tearDown() {
        wm.stop()
    }

    @Test
    fun `acquireToken caches access_token across calls`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/tokenP"))
                .willReturn(
                    okJson(
                        """{"access_token":"TOKEN_AAA","token_type":"Bearer","expires_in":86400}""",
                    ),
                ),
        )
        val first = session.acquireToken("appkey", "appsecret")
        val second = session.acquireToken("appkey", "appsecret")
        first shouldBe "TOKEN_AAA"
        second shouldBe "TOKEN_AAA"
        wm.verify(1, postRequestedFor(urlEqualTo("/oauth2/tokenP")))
    }

    @Test
    fun `invalidateToken forces re-fetch`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/tokenP"))
                .inScenario("seq")
                .whenScenarioStateIs("Started")
                .willReturn(okJson("""{"access_token":"AAA","token_type":"Bearer","expires_in":86400}"""))
                .willSetStateTo("second"),
        )
        wm.stubFor(
            post(urlEqualTo("/oauth2/tokenP"))
                .inScenario("seq")
                .whenScenarioStateIs("second")
                .willReturn(okJson("""{"access_token":"BBB","token_type":"Bearer","expires_in":86400}""")),
        )
        session.acquireToken("appkey", "appsecret") shouldBe "AAA"
        session.invalidateToken("appkey")
        session.acquireToken("appkey", "appsecret") shouldBe "BBB"
        wm.verify(2, postRequestedFor(urlEqualTo("/oauth2/tokenP")))
    }

    @Test
    fun `refresh sends grant_type and credentials`() {
        wm.stubFor(
            post(urlEqualTo("/oauth2/tokenP"))
                .withRequestBody(matchingJsonPath("$.grant_type", equalTo("client_credentials")))
                .withRequestBody(matchingJsonPath("$.appkey", equalTo("ak1")))
                .withRequestBody(matchingJsonPath("$.appsecret", equalTo("as1")))
                .willReturn(okJson("""{"access_token":"OK","token_type":"Bearer","expires_in":86400}""")),
        )
        session.acquireToken("ak1", "as1") shouldBe "OK"
    }
}

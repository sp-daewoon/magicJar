package com.mj.domain.market.kis

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class KisTokenStoreTest {
    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `appKey가 비면 호출 없이 IllegalStateException`() {
        val props = KisProperties(appKey = null, appSecret = null, baseUrl = server.url("/").toString())
        val store = KisTokenStore(props, Clock.systemUTC(), RestClient.builder())

        shouldThrow<IllegalStateException> { store.currentTokenOrIssue() }
    }

    @Test
    fun `토큰을 1회 발급하고 캐시한다`() {
        val props =
            KisProperties(
                appKey = "AK",
                appSecret = "AS",
                baseUrl = server.url("/").toString(),
                tokenTtlSeconds = 3600,
            )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"TOK","token_type":"Bearer","expires_in":86400}"""),
        )
        val clock = Clock.fixed(Instant.parse("2026-04-25T00:00:00Z"), ZoneOffset.UTC)
        val store = KisTokenStore(props, clock, RestClient.builder())

        val first = store.currentTokenOrIssue()
        val second = store.currentTokenOrIssue()

        first shouldBe "TOK"
        second shouldBe "TOK"
        server.requestCount shouldBe 1
    }

    @Test
    fun `invalidate 후에는 재발급한다`() {
        val props =
            KisProperties(
                appKey = "AK",
                appSecret = "AS",
                baseUrl = server.url("/").toString(),
                tokenTtlSeconds = 3600,
            )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"FIRST","expires_in":86400}"""),
        )
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/json")
                .setBody("""{"access_token":"SECOND","expires_in":86400}"""),
        )
        val store = KisTokenStore(props, Clock.systemUTC(), RestClient.builder())

        store.currentTokenOrIssue() shouldBe "FIRST"
        store.invalidate()
        store.currentTokenOrIssue() shouldBe "SECOND"
        server.requestCount shouldBe 2
    }
}

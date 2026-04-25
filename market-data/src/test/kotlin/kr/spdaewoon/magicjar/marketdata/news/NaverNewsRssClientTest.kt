package kr.spdaewoon.magicjar.marketdata.news

import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.web.client.RestClient

class NaverNewsRssClientTest {
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
    fun `RSS XML을 NewsItem으로 정규화한다`() {
        val xml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <rss version="2.0">
              <channel>
                <title>네이버 금융</title>
                <item>
                  <title>삼성전자 신고가</title>
                  <link>https://example.com/1</link>
                  <description>본문 1</description>
                  <pubDate>Sat, 25 Apr 2026 09:00:00 +0900</pubDate>
                </item>
                <item>
                  <title>SK하이닉스 실적</title>
                  <link>https://example.com/2</link>
                  <description>본문 2</description>
                  <pubDate>Sat, 25 Apr 2026 10:00:00 +0900</pubDate>
                </item>
              </channel>
            </rss>
            """.trimIndent()
        server.enqueue(
            MockResponse()
                .setHeader("Content-Type", "application/xml; charset=utf-8")
                .setBody(xml),
        )

        val props = NewsProperties(rssUrls = listOf(server.url("/rss").toString()))
        val client = NaverNewsRssClient(props, RestClient.builder())

        val items = client.fetchAll()

        items shouldHaveSize 2
        items[0].title shouldBe "삼성전자 신고가"
        items[0].source shouldBe "NAVER_RSS"
        items[1].title shouldBe "SK하이닉스 실적"
    }

    @Test
    fun `네트워크 실패는 빈 리스트로 처리한다`() {
        server.enqueue(MockResponse().setResponseCode(500))
        val props = NewsProperties(rssUrls = listOf(server.url("/rss").toString()))
        val client = NaverNewsRssClient(props, RestClient.builder())

        client.fetchAll() shouldHaveSize 0
    }
}

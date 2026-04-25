package kr.spdaewoon.magicjar.marketdata.news

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.dataformat.xml.XmlMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.spdaewoon.magicjar.core.domain.NewsItem
import org.slf4j.LoggerFactory
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 네이버 금융 RSS 수집 클라이언트.
 *
 * - User-Agent 명시(robots/이용약관 준수).
 * - RSS XML → NewsItem 으로 정규화. source="NAVER_RSS".
 * - 네트워크 실패는 빈 리스트 반환 + WARN 로그(전체 부팅·스케줄러 실패하지 않게).
 */
@Component
class NaverNewsRssClient(
    private val properties: NewsProperties,
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(NaverNewsRssClient::class.java)

    private val rest: RestClient =
        restClientBuilder
            .defaultHeader(HttpHeaders.USER_AGENT, properties.userAgent)
            .build()

    private val xmlMapper: XmlMapper =
        XmlMapper().apply {
            registerKotlinModule()
        }

    fun fetchAll(): List<NewsItem> = properties.rssUrls.flatMap { fetch(it) }

    fun fetch(url: String): List<NewsItem> =
        try {
            val bytes =
                rest
                    .get()
                    .uri(url)
                    .retrieve()
                    .body(ByteArray::class.java)
                    ?: ByteArray(0)
            val xml = String(bytes, Charsets.UTF_8)
            parseRss(xml).also { log.debug("Fetched {} items from {}", it.size, url) }
        } catch (ex: Exception) {
            log.warn("Naver RSS fetch failed for {}: {}", url, ex.message)
            emptyList()
        }

    fun parseRss(xml: String): List<NewsItem> {
        if (xml.isBlank()) return emptyList()
        val rss = xmlMapper.readValue(xml, RssRoot::class.java)
        val items = rss.channel?.items.orEmpty()
        return items.map { it.toNewsItem() }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class RssRoot(
        val channel: Channel? = null,
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class Channel(
        @com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlElementWrapper(useWrapping = false)
        @com.fasterxml.jackson.annotation.JsonProperty("item")
        val items: List<Item> = emptyList(),
    )

    @JsonIgnoreProperties(ignoreUnknown = true)
    internal data class Item(
        val title: String = "",
        val link: String = "",
        val description: String = "",
        val pubDate: String? = null,
    ) {
        fun toNewsItem(): NewsItem =
            NewsItem(
                id = UUID.randomUUID(),
                source = "NAVER_RSS",
                publishedAt = parsePubDate(pubDate),
                title = title.trim(),
                body = description.trim().ifEmpty { link },
                instruments = emptyList(),
            )

        private fun parsePubDate(raw: String?): Instant {
            if (raw.isNullOrBlank()) return Instant.now()
            val tries =
                listOf(
                    DateTimeFormatter.RFC_1123_DATE_TIME,
                    DateTimeFormatter.ISO_OFFSET_DATE_TIME,
                )
            for (fmt in tries) {
                try {
                    return OffsetDateTime.parse(raw, fmt).toInstant()
                } catch (_: Exception) {
                    // try next
                }
            }
            return try {
                ZonedDateTime.parse(raw, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()
            } catch (_: Exception) {
                Instant.now().atOffset(ZoneOffset.UTC).toInstant()
            }
        }
    }
}

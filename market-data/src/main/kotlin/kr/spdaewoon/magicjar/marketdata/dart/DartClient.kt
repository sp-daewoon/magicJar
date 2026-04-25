package kr.spdaewoon.magicjar.marketdata.dart

import com.fasterxml.jackson.annotation.JsonProperty
import kr.spdaewoon.magicjar.core.domain.NewsItem
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.UUID

/**
 * 전자공시(DART) Open API 클라이언트.
 *
 * - apiKey 없으면 listFilings()는 emptyList() 반환(폴백) — 부팅 가능.
 * - 응답을 NewsItem 도메인으로 정규화. source="DART".
 */
@Component
class DartClient(
    private val properties: DartProperties,
    restClientBuilder: RestClient.Builder = RestClient.builder(),
) {
    private val log = LoggerFactory.getLogger(DartClient::class.java)

    private val rest: RestClient =
        restClientBuilder
            .baseUrl(properties.baseUrl)
            .build()

    fun listFilings(
        from: LocalDate,
        to: LocalDate,
        corpCode: String? = null,
    ): List<NewsItem> {
        if (!properties.hasCredentials()) {
            log.debug("DART apiKey missing — returning empty filings (from={}, to={})", from, to)
            return emptyList()
        }
        return try {
            val response =
                rest
                    .get()
                    .uri { builder ->
                        builder.path("/api/list.json")
                        builder.queryParam("crtfc_key", properties.apiKey)
                        if (corpCode != null) builder.queryParam("corp_code", corpCode)
                        builder.queryParam("bgn_de", from.format(YYYYMMDD))
                        builder.queryParam("end_de", to.format(YYYYMMDD))
                        builder.queryParam("page_count", "100")
                        builder.build()
                    }.retrieve()
                    .body(ListResponse::class.java)
            response?.list?.map { it.toNewsItem() } ?: emptyList()
        } catch (ex: Exception) {
            log.warn("DART listFilings failed: {}", ex.message)
            emptyList()
        }
    }

    internal data class ListResponse(
        @JsonProperty("status") val status: String? = null,
        @JsonProperty("message") val message: String? = null,
        @JsonProperty("list") val list: List<Filing> = emptyList(),
    )

    internal data class Filing(
        @JsonProperty("corp_code") val corpCode: String? = null,
        @JsonProperty("corp_name") val corpName: String? = null,
        @JsonProperty("stock_code") val stockCode: String? = null,
        @JsonProperty("report_nm") val reportName: String,
        @JsonProperty("rcept_no") val receiptNo: String,
        @JsonProperty("rcept_dt") val receiptDate: String,
    ) {
        fun toNewsItem(): NewsItem {
            val date = LocalDate.parse(receiptDate, YYYYMMDD)
            val publishedAt = date.atTime(LocalTime.of(9, 0)).atZone(KST).toInstant()
            return NewsItem(
                id = UUID.randomUUID(),
                source = "DART",
                publishedAt = publishedAt,
                title = "[$corpName] $reportName",
                body = "DART receipt $receiptNo",
                instruments = listOfNotNull(stockCode?.takeIf { it.isNotBlank() }),
            )
        }
    }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        @Suppress("unused")
        fun nowKst(): Instant = Instant.now()
    }
}

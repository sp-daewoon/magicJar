package com.mj.domain.market.kis

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker
import io.github.resilience4j.retry.annotation.Retry
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Quote
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.core.io.ResourceLoader
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * KIS 시세 조회 클라이언트.
 *
 * 사용 trId
 * - 현재가: FHKST01010100
 * - 일봉(국내주식기간별시세): FHKST03010100
 *
 * credentials가 없으면 classpath 의 kis-samples (quote.json / candles-d1.json) 폴백 응답으로 도메인 객체를 만든다(부팅·테스트 가능).
 */
@Component
@CircuitBreaker(name = "kisQuote")
@Retry(name = "kisQuote")
class KisQuoteApi(
    private val properties: KisProperties,
    private val rest: KisRestClient,
    private val resourceLoader: ResourceLoader,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.marketdata.kis.fallback-samples:true}") private val fallbackSamples: Boolean,
) {
    private val log = LoggerFactory.getLogger(KisQuoteApi::class.java)

    fun fetchCurrentPrice(code: String): Quote {
        if (!properties.hasCredentials() && fallbackSamples) {
            log.debug("KIS credentials missing — using sample quote for {}", code)
            return loadSampleQuote(code)
        }
        val response =
            rest.get(
                path = "/uapi/domestic-stock/v1/quotations/inquire-price",
                trId = "FHKST01010100",
                query =
                    mapOf(
                        "FID_COND_MRKT_DIV_CODE" to "J",
                        "FID_INPUT_ISCD" to code,
                    ),
                responseType = QuotePriceResponse::class.java,
            )
        return response.toDomain(code)
    }

    fun fetchDailyCandles(
        code: String,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        if (!properties.hasCredentials() && fallbackSamples) {
            log.debug("KIS credentials missing — using sample candles for {}", code)
            return loadSampleCandles(code)
        }
        val response =
            rest.get(
                path = "/uapi/domestic-stock/v1/quotations/inquire-daily-itemchartprice",
                trId = "FHKST03010100",
                query =
                    mapOf(
                        "FID_COND_MRKT_DIV_CODE" to "J",
                        "FID_INPUT_ISCD" to code,
                        "FID_INPUT_DATE_1" to from.format(YYYYMMDD),
                        "FID_INPUT_DATE_2" to to.format(YYYYMMDD),
                        "FID_PERIOD_DIV_CODE" to "D",
                        "FID_ORG_ADJ_PRC" to "0",
                    ),
                responseType = DailyChartResponse::class.java,
            )
        return response.toDomain(code)
    }

    private fun loadSampleQuote(code: String): Quote {
        val resource = resourceLoader.getResource("classpath:kis-samples/quote.json")
        val sample = objectMapper.readValue(resource.inputStream, SampleQuote::class.java)
        return Quote(
            code = code,
            at = Instant.parse(sample.at),
            price = BigDecimal(sample.price),
            volume = sample.volume,
        )
    }

    private fun loadSampleCandles(code: String): List<Candle> {
        val resource = resourceLoader.getResource("classpath:kis-samples/candles-d1.json")
        val sample = objectMapper.readValue(resource.inputStream, Array<SampleCandle>::class.java)
        return sample.map {
            Candle(
                code = code,
                interval = Interval.D1,
                openTime = LocalDate.parse(it.date).atStartOfDay(KST).toInstant(),
                open = BigDecimal(it.open),
                high = BigDecimal(it.high),
                low = BigDecimal(it.low),
                close = BigDecimal(it.close),
                volume = it.volume,
                tradeCount = it.tradeCount,
            )
        }
    }

    internal data class QuotePriceResponse(
        @JsonProperty("output") val output: Output,
    ) {
        data class Output(
            @JsonProperty("stck_prpr") val price: String,
            @JsonProperty("acml_vol") val accumulatedVolume: String,
        )

        fun toDomain(code: String): Quote =
            Quote(
                code = code,
                at = Instant.now(),
                price = BigDecimal(output.price),
                volume = output.accumulatedVolume.toLong(),
            )
    }

    internal data class DailyChartResponse(
        @JsonProperty("output2") val output2: List<Row> = emptyList(),
    ) {
        data class Row(
            @JsonProperty("stck_bsop_date") val date: String,
            @JsonProperty("stck_oprc") val open: String,
            @JsonProperty("stck_hgpr") val high: String,
            @JsonProperty("stck_lwpr") val low: String,
            @JsonProperty("stck_clpr") val close: String,
            @JsonProperty("acml_vol") val volume: String,
            @JsonProperty("acml_tr_pbmn") val tradeAmount: String? = null,
        )

        fun toDomain(code: String): List<Candle> =
            output2.map { row ->
                Candle(
                    code = code,
                    interval = Interval.D1,
                    openTime = LocalDate.parse(row.date, YYYYMMDD).atStartOfDay(KST).toInstant(),
                    open = BigDecimal(row.open),
                    high = BigDecimal(row.high),
                    low = BigDecimal(row.low),
                    close = BigDecimal(row.close),
                    volume = row.volume.toLong(),
                    tradeCount = row.tradeAmount?.toLongOrNull() ?: 0L,
                )
            }
    }

    data class SampleQuote(
        val at: String,
        val price: String,
        val volume: Long,
    )

    data class SampleCandle(
        val date: String,
        val open: String,
        val high: String,
        val low: String,
        val close: String,
        val volume: Long,
        val tradeCount: Long,
    )

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val YYYYMMDD: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd")

        @Suppress("unused")
        fun localDateTimeOf(
            date: LocalDate,
            time: LocalTime = LocalTime.MIDNIGHT,
        ): LocalDateTime = LocalDateTime.of(date, time)
    }
}

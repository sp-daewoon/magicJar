package com.mj.batch.infrastructure.kis

import com.fasterxml.jackson.annotation.JsonProperty
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.common.Quote
import com.mj.domain.market.port.KisRestPort
import org.slf4j.LoggerFactory
import org.springframework.context.annotation.Profile
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.time.LocalDate
import java.time.ZoneId

/**
 * stub-kis 프로파일용 KisRestPort 구현체.
 *
 * - classpath:kis-samples/candles-d1-005930.json 로드
 * - fetchCandles: from~to 범위 필터링
 * - fetchQuote: 마지막 캔들 기준 고정 Quote 반환
 * - token(): "stub-token" 반환 (인증 불필요)
 *
 * KIS 모의/실계좌 키 없이도 동작하므로 CI/CD·백테스트 개발 환경에서 기본 어댑터로 사용.
 */
@Component
@Profile("!real-kis")
class StubKisRestAdapter(
    private val objectMapper: ObjectMapper,
) : KisRestPort {
    private val log = LoggerFactory.getLogger(StubKisRestAdapter::class.java)
    private val zone = ZoneId.of("Asia/Seoul")

    private data class CandleRow(
        @JsonProperty("date") val date: String,
        @JsonProperty("open") val open: String,
        @JsonProperty("high") val high: String,
        @JsonProperty("low") val low: String,
        @JsonProperty("close") val close: String,
        @JsonProperty("volume") val volume: Long,
        @JsonProperty("tradeCount") val tradeCount: Long,
    )

    /** 종목코드 → 캔들 리스트 캐시 (JVM 기동 중 고정) */
    private val candleCache: Map<String, List<CandleRow>> by lazy {
        loadSamples()
    }

    private fun loadSamples(): Map<String, List<CandleRow>> {
        val resource = ClassPathResource("kis-samples/candles-d1-005930.json")
        return if (resource.exists()) {
            val rows: List<CandleRow> = objectMapper.readValue(resource.inputStream)
            log.info("[stub-kis] loaded {} candle rows for 005930", rows.size)
            mapOf("005930" to rows)
        } else {
            log.warn("[stub-kis] kis-samples/candles-d1-005930.json not found — empty fixture")
            emptyMap()
        }
    }

    override fun fetchCandles(
        code: String,
        interval: Interval,
        from: LocalDate,
        to: LocalDate,
    ): List<Candle> {
        val rows =
            candleCache[code] ?: run {
                log.warn("[stub-kis] fetchCandles: no stub data for code={}", code)
                return emptyList()
            }
        val filtered =
            rows.filter { row ->
                val d = LocalDate.parse(row.date)
                !d.isBefore(from) && !d.isAfter(to)
            }
        log.debug("[stub-kis] fetchCandles code={} from={} to={} => {} rows", code, from, to, filtered.size)
        return filtered.map { row ->
            val openTime =
                LocalDate
                    .parse(row.date)
                    .atStartOfDay(zone)
                    .toInstant()
            Candle(
                code = code,
                interval = interval,
                openTime = openTime,
                open = BigDecimal(row.open),
                high = BigDecimal(row.high),
                low = BigDecimal(row.low),
                close = BigDecimal(row.close),
                volume = row.volume,
                tradeCount = row.tradeCount,
            )
        }
    }

    override fun fetchQuote(code: String): Quote {
        val rows = candleCache[code]
        val lastRow = rows?.lastOrNull()
        val price = lastRow?.let { BigDecimal(it.close) } ?: BigDecimal("70000")
        val volume = lastRow?.volume ?: 10000000L
        log.debug("[stub-kis] fetchQuote code={} price={}", code, price)
        return Quote(
            code = code,
            at = java.time.Instant.now(),
            price = price,
            volume = volume,
        )
    }

    override fun token(): String = "stub-token"
}

package kr.spdaewoon.magicjar.consumer.infrastructure.yahoo

import kr.spdaewoon.magicjar.domain.market.candle.Candle
import kr.spdaewoon.magicjar.domain.market.candle.CandleInterval
import kr.spdaewoon.magicjar.domain.market.port.YahooCandleFetcher
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.util.retry.Retry
import java.math.BigDecimal
import java.time.Duration
import kotlinx.datetime.Instant as KtInstant

/**
 * Yahoo Finance v8 chart endpoint 어댑터 (ADR 0019 §5-7·5-8 정합).
 * - URL: https://query1.finance.yahoo.com/v8/finance/chart/{ticker}?interval={...}&range={...}
 * - ticker suffix: .KS (KOSPI) → .KQ (KOSDAQ) fallback (instrument market='UNKNOWN' 정합)
 * - HTTP 429·404 → 60s 대기 + 1회 재시도
 * - source = "YAHOO"
 *
 * 룰 5 위반 금지: LLM provider import 0건.
 */
@Component
class YahooCandleFetcherAdapter(
    private val webClient: WebClient,
) : YahooCandleFetcher {
    private val log = LoggerFactory.getLogger(YahooCandleFetcherAdapter::class.java)

    companion object {
        private const val YAHOO_BASE = "https://query1.finance.yahoo.com"
        private const val SOURCE = "YAHOO"

        /** interval → Yahoo range 파라미터 매핑 (ADR 0019 §5-2 정합) */
        private val INTERVAL_TO_RANGE =
            mapOf(
                CandleInterval.ONE_MIN to "7d",
                CandleInterval.FIVE_MIN to "60d",
                CandleInterval.FIFTEEN_MIN to "60d",
                CandleInterval.THIRTY_MIN to "60d",
                CandleInterval.NINETY_MIN to "60d",
                CandleInterval.ONE_HOUR to "2y",
                CandleInterval.ONE_DAY to "max",
            )

        /** Yahoo interval 문자열 매핑 */
        private fun toYahooInterval(interval: CandleInterval): String =
            when (interval) {
                CandleInterval.ONE_MIN -> "1m"
                CandleInterval.FIVE_MIN -> "5m"
                CandleInterval.FIFTEEN_MIN -> "15m"
                CandleInterval.THIRTY_MIN -> "30m"
                CandleInterval.NINETY_MIN -> "90m"
                CandleInterval.ONE_HOUR -> "1h"
                CandleInterval.ONE_DAY -> "1d"
                else -> throw IllegalArgumentException("Yahoo 미지원 interval: ${interval.code}")
            }
    }

    override fun fetch(
        ticker: String,
        interval: CandleInterval,
        rangeDays: Int,
    ): List<Candle> {
        val range = INTERVAL_TO_RANGE[interval] ?: "${rangeDays}d"
        val yahooInterval = toYahooInterval(interval)

        // .KS 시도 → .KQ fallback (instrument market='UNKNOWN' 정합)
        return fetchWithSuffix(ticker, ".KS", yahooInterval, range, interval)
            ?: fetchWithSuffix(ticker, ".KQ", yahooInterval, range, interval)
            ?: run {
                log.warn("Yahoo cold-start 조회 실패 (KS+KQ 모두): ticker={} interval={}", ticker, interval.code)
                emptyList()
            }
    }

    private fun fetchWithSuffix(
        ticker: String,
        suffix: String,
        yahooInterval: String,
        range: String,
        domainInterval: CandleInterval,
    ): List<Candle>? {
        val symbol = "$ticker$suffix"
        return try {
            val body =
                webClient
                    .get()
                    .uri("$YAHOO_BASE/v8/finance/chart/$symbol?interval=$yahooInterval&range=$range")
                    .header("User-Agent", "Mozilla/5.0")
                    .retrieve()
                    .bodyToMono(Map::class.java)
                    .timeout(Duration.ofSeconds(15))
                    .retryWhen(
                        Retry.fixedDelay(1, Duration.ofSeconds(60))
                            .filter { e ->
                                e is WebClientResponseException &&
                                    (e.statusCode == HttpStatus.TOO_MANY_REQUESTS || e.statusCode == HttpStatus.NOT_FOUND)
                            },
                    ).block()
                    ?: return null

            parseYahooResponse(body, ticker, domainInterval)
        } catch (e: WebClientResponseException) {
            if (e.statusCode == HttpStatus.NOT_FOUND || e.statusCode == HttpStatus.TOO_MANY_REQUESTS) {
                log.debug("Yahoo fetch {} fail: {} {}", symbol, e.statusCode, e.message)
                null
            } else {
                log.warn("Yahoo fetch 오류 {}: {} {}", symbol, e.statusCode, e.message)
                null
            }
        } catch (e: Exception) {
            log.warn("Yahoo fetch 예외 {}: {}", symbol, e.message)
            null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseYahooResponse(
        body: Map<*, *>,
        code: String,
        interval: CandleInterval,
    ): List<Candle>? {
        return try {
            val chart = body["chart"] as? Map<*, *> ?: return null
            val results = chart["result"] as? List<*> ?: return null
            val result = results.firstOrNull() as? Map<*, *> ?: return null
            val timestamps = result["timestamp"] as? List<*> ?: return emptyList()
            val indicators = result["indicators"] as? Map<*, *> ?: return emptyList()
            val quotes = (indicators["quote"] as? List<*>)?.firstOrNull() as? Map<*, *> ?: return emptyList()

            val opens = quotes["open"] as? List<*> ?: emptyList<Any?>()
            val highs = quotes["high"] as? List<*> ?: emptyList<Any?>()
            val lows = quotes["low"] as? List<*> ?: emptyList<Any?>()
            val closes = quotes["close"] as? List<*> ?: emptyList<Any?>()
            val volumes = quotes["volume"] as? List<*> ?: emptyList<Any?>()

            val candles = mutableListOf<Candle>()
            for (i in timestamps.indices) {
                val epochSecond = (timestamps[i] as? Number)?.toLong() ?: continue
                val open = (opens.getOrNull(i) as? Number)?.let { BigDecimal(it.toString()) } ?: continue
                val high = (highs.getOrNull(i) as? Number)?.let { BigDecimal(it.toString()) } ?: continue
                val low = (lows.getOrNull(i) as? Number)?.let { BigDecimal(it.toString()) } ?: continue
                val close = (closes.getOrNull(i) as? Number)?.let { BigDecimal(it.toString()) } ?: continue
                val volume = (volumes.getOrNull(i) as? Number)?.toLong() ?: 0L

                candles.add(
                    Candle(
                        code = code,
                        interval = interval,
                        ts = KtInstant.fromEpochSeconds(epochSecond),
                        openRaw = open,
                        highRaw = high,
                        lowRaw = low,
                        closeRaw = close,
                        openAdj = null,
                        highAdj = null,
                        lowAdj = null,
                        closeAdj = null,
                        volume = volume,
                        source = SOURCE,
                    ),
                )
            }
            log.debug("Yahoo 파싱 완료: code={} interval={} count={}", code, interval.code, candles.size)
            candles
        } catch (e: Exception) {
            log.warn("Yahoo 응답 파싱 실패: code={} interval={} err={}", code, interval.code, e.message)
            null
        }
    }
}

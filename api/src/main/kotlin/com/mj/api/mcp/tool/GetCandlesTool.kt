package com.mj.api.mcp.tool

import com.fasterxml.jackson.databind.JsonNode
import com.mj.api.application.market.CandleQueryService
import com.mj.domain.common.Interval
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * `get_candles` (read) — spec §4-1.
 *
 * 한국 주식 종목의 OHLCV 캔들 시계열 조회 (수정주가 default).
 * 기존 [CandleQueryService] 그대로 활용 — adjusted 플래그는 향후 보강 (현재 도메인 candle 1종).
 */
@Component
class GetCandlesTool(
    private val candleQueryService: CandleQueryService,
) : McpTool {
    override val name: String = "get_candles"
    override val description: String =
        "한국 주식 종목의 OHLCV 캔들 시계열을 조회한다. KRX 6자리 코드 + interval(D1/M1/M5/M15/M30/H1) 기준. " +
            "from/to 미명시 시 봉별 적정 lookback 자동 적용. 수정주가 기본."

    override val inputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "code" to
                        mapOf(
                            "type" to "string",
                            "pattern" to "^[0-9]{6}$",
                            "description" to "KRX 6자리 종목코드 (예: 005930)",
                        ),
                    "interval" to
                        mapOf(
                            "type" to "string",
                            "enum" to listOf("D1", "M1", "M5", "M15", "M30", "H1", "DAY"),
                            "description" to "캔들 봉 단위 (default D1)",
                        ),
                    "from" to
                        mapOf(
                            "type" to "string",
                            "format" to "date-time",
                            "description" to "조회 시작 시각 ISO-8601 (옵션)",
                        ),
                    "to" to
                        mapOf(
                            "type" to "string",
                            "format" to "date-time",
                            "description" to "조회 종료 시각 ISO-8601 (옵션, default now)",
                        ),
                    "adjusted" to
                        mapOf(
                            "type" to "boolean",
                            "description" to "수정주가 여부 (R1는 항상 true 처리)",
                        ),
                ),
            "required" to listOf("code"),
        )

    override val outputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "code" to mapOf("type" to "string"),
                    "interval" to mapOf("type" to "string"),
                    "candles" to
                        mapOf(
                            "type" to "array",
                            "items" to
                                mapOf(
                                    "type" to "object",
                                    "properties" to
                                        mapOf(
                                            "timestamp" to mapOf("type" to "string"),
                                            "open" to mapOf("type" to "number"),
                                            "high" to mapOf("type" to "number"),
                                            "low" to mapOf("type" to "number"),
                                            "close" to mapOf("type" to "number"),
                                            "volume" to mapOf("type" to "integer"),
                                        ),
                                ),
                        ),
                    "count" to mapOf("type" to "integer"),
                ),
        )

    override fun invoke(arguments: JsonNode?): Any {
        val code = arguments?.get("code")?.asText() ?: error("code required")
        val intervalRaw = arguments.get("interval")?.asText() ?: "D1"
        val interval = CandleQueryService.parseInterval(intervalRaw)

        val from =
            arguments
                .get("from")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) }
        val to =
            arguments
                .get("to")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) }

        val candles = candleQueryService.query(code, interval, from, to)

        return mapOf(
            "code" to code,
            "interval" to interval.name,
            "candles" to candles.map { c -> serializeCandle(c.openTime, interval, c) },
            "count" to candles.size,
        )
    }

    private fun serializeCandle(
        openTime: Instant,
        interval: Interval,
        c: com.mj.domain.common.Candle,
    ): Map<String, Any> =
        mapOf(
            "timestamp" to formatTimestamp(openTime, interval),
            "open" to c.open.toPlainString(),
            "high" to c.high.toPlainString(),
            "low" to c.low.toPlainString(),
            "close" to c.close.toPlainString(),
            "volume" to c.volume,
        )

    private fun formatTimestamp(
        openTime: Instant,
        interval: Interval,
    ): String =
        when (interval) {
            Interval.D1 -> openTime.atZone(KST).toLocalDate().format(DATE_FMT)
            else -> openTime.atOffset(ZoneOffset.UTC).toEpochSecond().toString()
        }

    companion object {
        private val KST: ZoneId = ZoneId.of("Asia/Seoul")
        private val DATE_FMT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}

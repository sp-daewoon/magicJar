package com.mj.api.mcp.tool

import com.fasterxml.jackson.databind.JsonNode
import com.mj.api.application.market.QuoteQueryService
import com.mj.api.mcp.McpErrorCodes
import com.mj.api.mcp.McpException
import com.mj.domain.market.session.MarketSession
import com.mj.domain.market.session.MarketSessionState
import org.springframework.stereotype.Component

/**
 * `get_quote` (read) — spec §4-2.
 *
 * 3-tier fallback (LIVE → CACHED → LAST_DAY_CLOSE) 단일 종목 현재가 조회.
 */
@Component
class GetQuoteTool(
    private val quoteQueryService: QuoteQueryService,
) : McpTool {
    override val name: String = "get_quote"
    override val description: String =
        "단일 종목의 현재가를 3-tier fallback(LIVE → CACHED → LAST_DAY_CLOSE)으로 조회한다. " +
            "stale 플래그·source·세션 상태를 함께 반환."

    override val inputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "code" to
                        mapOf(
                            "type" to "string",
                            "pattern" to "^[0-9]{6}$",
                            "description" to "KRX 6자리 종목코드",
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
                    "price" to mapOf("type" to "number"),
                    "source" to mapOf("type" to "string"),
                    "stale" to mapOf("type" to "boolean"),
                    "session" to mapOf("type" to "string"),
                    "asOf" to mapOf("type" to "string"),
                ),
        )

    override fun invoke(arguments: JsonNode?): Any {
        val code = arguments?.get("code")?.asText() ?: error("code required")
        val result =
            quoteQueryService.query(code)
                ?: throw McpException(
                    McpErrorCodes.TOOL_EXECUTION_ERROR,
                    "no quote available for code=$code (no cache, no last-day candle)",
                )
        return mapOf(
            "code" to result.code,
            "price" to result.price.toPlainString(),
            "volume" to (result.volume ?: 0L),
            "source" to result.source.name,
            "stale" to result.stale,
            "session" to sessionLabel(result.session),
            "asOf" to result.asOf.toString(),
        )
    }

    private fun sessionLabel(s: MarketSession?): String =
        when (val st = s?.state) {
            MarketSessionState.Open -> "OPEN"
            MarketSessionState.PreMarket -> "PRE_MARKET"
            MarketSessionState.PostMarket -> "POST_MARKET"
            is MarketSessionState.Closed -> "CLOSED:${st.reason}"
            is MarketSessionState.Holiday -> "HOLIDAY:${st.name}"
            null -> "UNKNOWN"
        }
}

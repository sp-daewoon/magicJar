package com.mj.api.mcp.tool

import com.fasterxml.jackson.databind.JsonNode
import com.mj.api.application.instrument.InstrumentService
import org.springframework.stereotype.Component

/**
 * `search_instruments` (read) — spec §4-3.
 *
 * 한글·영문·종목코드 부분 일치로 종목 마스터 검색.
 */
@Component
class SearchInstrumentsTool(
    private val instrumentService: InstrumentService,
) : McpTool {
    override val name: String = "search_instruments"
    override val description: String =
        "한국 주식 종목을 한글·영문·종목코드로 검색한다. KOSPI·KOSDAQ·ETF·ETN 등 모든 universe 포함."

    override val inputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "query" to
                        mapOf(
                            "type" to "string",
                            "minLength" to 1,
                            "description" to "검색 키워드 (한글/영문/코드 일부)",
                        ),
                    "limit" to
                        mapOf(
                            "type" to "integer",
                            "minimum" to 1,
                            "maximum" to 100,
                            "description" to "결과 최대 건수 (default 20)",
                        ),
                ),
            "required" to listOf("query"),
        )

    override val outputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "instruments" to
                        mapOf(
                            "type" to "array",
                            "items" to
                                mapOf(
                                    "type" to "object",
                                    "properties" to
                                        mapOf(
                                            "code" to mapOf("type" to "string"),
                                            "name" to mapOf("type" to "string"),
                                            "market" to mapOf("type" to "string"),
                                        ),
                                ),
                        ),
                    "count" to mapOf("type" to "integer"),
                ),
        )

    override fun invoke(arguments: JsonNode?): Any {
        val query = arguments?.get("query")?.asText() ?: error("query required")
        val limit = arguments.get("limit")?.asInt() ?: 20
        val list = instrumentService.search(query, limit)
        return mapOf(
            "instruments" to
                list.map { i ->
                    mapOf(
                        "code" to i.code,
                        "name" to i.name,
                        "market" to i.market.name,
                        "type" to i.type.name,
                    )
                },
            "count" to list.size,
        )
    }
}

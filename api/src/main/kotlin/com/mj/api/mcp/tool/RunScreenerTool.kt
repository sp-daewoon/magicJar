package com.mj.api.mcp.tool

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.mj.api.application.screener.ScreenerExecutor
import com.mj.api.infrastructure.dsl.StrategyDslParser
import com.mj.api.infrastructure.persistence.screener.ScreenMatchJpaRepository
import com.mj.api.infrastructure.persistence.screener.ScreenRunJpaRepository
import com.mj.api.mcp.McpErrorCodes
import com.mj.api.mcp.McpException
import com.mj.domain.strategy.screener.ScreenRequest
import com.mj.domain.strategy.screener.Universe
import org.springframework.stereotype.Component
import java.time.LocalDate

/**
 * `run_screener` (analysis) — spec §4-4.
 *
 * 유니버스 × DSL 전략으로 매칭 종목 발굴. 룰 기반 — AI 호출 0.
 * 동기 실행 (소규모 universe 가정 — KOSPI200·KOSDAQ150·CUSTOM).
 *
 * 응답: runId(audit trail) + 매칭 종목 list. 실제 결과 조회는 REST `GET /api/screener/{id}` 와 동일.
 */
@Component
class RunScreenerTool(
    private val screenerExecutor: ScreenerExecutor,
    private val dslParser: StrategyDslParser,
    private val screenRunRepo: ScreenRunJpaRepository,
    private val screenMatchRepo: ScreenMatchJpaRepository,
) : McpTool {
    override val name: String = "run_screener"
    override val description: String =
        "유니버스(KOSPI200·KOSDAQ150·ETF·ETN·ALL_LISTED·CUSTOM) × DSL 전략으로 매칭 종목을 스크리닝한다. " +
            "룰 기반·AI 호출 0. 동기 실행."

    override val inputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "universe" to
                        mapOf(
                            "type" to "string",
                            "enum" to listOf("KOSPI200", "KOSDAQ150", "ETF", "ETN", "ALL_LISTED", "CUSTOM"),
                            "description" to "검색 대상 유니버스",
                        ),
                    "customCodes" to
                        mapOf(
                            "type" to "array",
                            "items" to mapOf("type" to "string", "pattern" to "^[0-9]{6}$"),
                            "description" to "universe=CUSTOM 일 때 필수",
                        ),
                    "dslYaml" to
                        mapOf(
                            "type" to "string",
                            "minLength" to 1,
                            "description" to ".kis.yaml superset 전략 DSL",
                        ),
                    "asOfDate" to
                        mapOf(
                            "type" to "string",
                            "format" to "date",
                            "description" to "기준일 (default 오늘)",
                        ),
                ),
            "required" to listOf("universe", "dslYaml"),
        )

    override val outputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "runId" to mapOf("type" to "string"),
                    "totalScanned" to mapOf("type" to "integer"),
                    "matchCount" to mapOf("type" to "integer"),
                    "matches" to
                        mapOf(
                            "type" to "array",
                            "items" to
                                mapOf(
                                    "type" to "object",
                                    "properties" to
                                        mapOf(
                                            "code" to mapOf("type" to "string"),
                                            "name" to mapOf("type" to "string"),
                                            "entryReasons" to mapOf("type" to "array"),
                                        ),
                                ),
                        ),
                ),
        )

    override fun invoke(arguments: JsonNode?): Any {
        val universeRaw = arguments?.get("universe")?.asText() ?: error("universe required")
        val dslYaml = arguments.get("dslYaml")?.asText() ?: error("dslYaml required")
        val asOfDate =
            arguments
                .get("asOfDate")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.let { LocalDate.parse(it) }
                ?: LocalDate.now()
        val customCodes =
            arguments.get("customCodes")?.let { node ->
                if (node.isArray) node.map { it.asText() } else null
            }

        val universe = parseUniverse(universeRaw, customCodes)

        val dsl =
            try {
                dslParser.parse(dslYaml)
            } catch (e: Exception) {
                throw McpException(
                    McpErrorCodes.INVALID_PARAMS,
                    "Invalid params: dslYaml parse failed: ${e.message}",
                )
            }

        val request = ScreenRequest(universe = universe, dsl = dsl, asOfDate = asOfDate)
        val runId = screenerExecutor.execute(request = request, strategyYaml = dslYaml)

        val run =
            screenRunRepo.findByRunId(runId)
                ?: throw McpException(McpErrorCodes.TOOL_EXECUTION_ERROR, "screen_run lost: $runId")

        val matches = screenMatchRepo.findByScreenRunId(run.id)
        val mapper = jacksonObjectMapper()

        return mapOf(
            "runId" to runId,
            "status" to run.status,
            "totalScanned" to run.totalScanned,
            "matchCount" to run.matchCount,
            "skipCount" to run.skipCount,
            "matches" to
                matches.map { m ->
                    mapOf(
                        "code" to m.code,
                        "name" to m.name,
                        "entryReasons" to parseReasons(m.entryReason, mapper),
                        "matchedAt" to m.matchedAt.toString(),
                    )
                },
        )
    }

    private fun parseUniverse(
        raw: String,
        customCodes: List<String>?,
    ): Universe =
        when (raw.uppercase()) {
            "KOSPI200" -> Universe.Kospi200
            "KOSDAQ150" -> Universe.Kosdaq150
            "ETF" -> Universe.Etf
            "ETN" -> Universe.Etn
            "ALL_LISTED" -> Universe.AllListed
            "CUSTOM" -> {
                if (customCodes.isNullOrEmpty()) {
                    throw McpException(McpErrorCodes.INVALID_PARAMS, "Invalid params: customCodes required for universe=CUSTOM")
                }
                Universe.Custom(customCodes)
            }
            else -> throw McpException(McpErrorCodes.INVALID_PARAMS, "Invalid params: unknown universe '$raw'")
        }

    private fun parseReasons(
        json: String,
        mapper: com.fasterxml.jackson.databind.ObjectMapper,
    ): List<Map<String, Any?>> =
        runCatching {
            @Suppress("UNCHECKED_CAST")
            mapper.readValue(json, List::class.java) as List<Map<String, Any?>>
        }.getOrElse { emptyList() }
}

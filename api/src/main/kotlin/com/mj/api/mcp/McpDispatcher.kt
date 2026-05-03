package com.mj.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.databind.node.TextNode
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component

/**
 * MCP method 분기 (T17 / spec §3).
 *
 * 지원 method:
 *  - `initialize` — capability handshake
 *  - `notifications/initialized` — client ack (server는 빈 응답)
 *  - `tools/list` — tool 카탈로그
 *  - `tools/call` — tool 실행
 *
 * 그 외 method → -32601 METHOD_NOT_FOUND.
 */
@Component
class McpDispatcher(
    private val registry: McpToolRegistry,
    private val objectMapper: ObjectMapper,
    @Value("\${magicjar.mcp.protocol-version:2025-03-26}") private val protocolVersion: String,
    @Value("\${magicjar.mcp.server-name:magicjar-mcp}") private val serverName: String,
    @Value("\${magicjar.mcp.server-version:0.1.0}") private val serverVersion: String,
) {
    private val log = LoggerFactory.getLogger(McpDispatcher::class.java)

    fun dispatch(request: McpJsonRpcRequest): Any? =
        when (request.method) {
            "initialize" -> handleInitialize()
            "notifications/initialized" -> null // server 응답 없음
            "tools/list" -> handleToolsList()
            "tools/call" -> handleToolsCall(request.params)
            else ->
                throw McpException(
                    McpErrorCodes.METHOD_NOT_FOUND,
                    "Method not found: ${request.method}",
                )
        }

    private fun handleInitialize(): Map<String, Any> =
        mapOf(
            "protocolVersion" to protocolVersion,
            "capabilities" to
                mapOf(
                    "tools" to mapOf("listChanged" to false),
                    "resources" to emptyMap<String, Any>(),
                    "prompts" to emptyMap<String, Any>(),
                ),
            "serverInfo" to
                mapOf(
                    "name" to serverName,
                    "version" to serverVersion,
                ),
            "instructions" to
                "magicJar는 한국 주식 자동매매 시스템의 read-only MCP 서버입니다. " +
                "캔들·시세·종목·매매일지·스크리너를 호출할 수 있으나 매매 트리거(주문 발행)는 노출하지 않습니다.",
        )

    private fun handleToolsList(): Map<String, Any> = mapOf("tools" to registry.catalog())

    private fun handleToolsCall(params: JsonNode?): Map<String, Any> {
        if (params == null || !params.isObject) {
            throw McpException(McpErrorCodes.INVALID_PARAMS, "Invalid params: tools/call requires {name, arguments}")
        }
        val name = params.get("name")?.asText() ?: throw McpException(McpErrorCodes.INVALID_PARAMS, "Invalid params: name required")
        val tool = registry.get(name) ?: throw McpException(McpErrorCodes.TOOL_NOT_FOUND, "Tool not found: $name")

        if (!tool.readOnly) {
            // R1 등록된 모든 tool은 read_only — 안전망. 향후 등록 실수 차단.
            throw McpException(McpErrorCodes.FORBIDDEN_TOOL, "Forbidden: write tools are not exposed (ADR 0045)")
        }

        val arguments = params.get("arguments")
        registry.validate(tool.inputSchema, arguments)

        val payload =
            try {
                tool.invoke(arguments)
            } catch (e: McpException) {
                throw e
            } catch (e: Exception) {
                log.error("[MCP] tool '$name' invocation failed", e)
                throw McpException(McpErrorCodes.TOOL_EXECUTION_ERROR, "Tool '$name' failed: ${e.message}")
            }

        // MCP 표준 응답 — content[0].text(JSON 직렬화)
        val payloadJson = objectMapper.writeValueAsString(payload)
        val factory = JsonNodeFactory.instance
        val contentItem: ObjectNode =
            factory
                .objectNode()
                .put("type", "text")
                .set<ObjectNode>("text", TextNode.valueOf(payloadJson))
        return mapOf(
            "content" to listOf(contentItem),
            "isError" to false,
            "structuredContent" to payload, // Anthropic 비표준 보강 — 디버깅 편의
        )
    }
}

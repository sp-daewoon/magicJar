package com.mj.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.NullNode
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.slf4j.LoggerFactory
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.util.UUID

/**
 * magicJar MCP 서버 endpoint (T17 / ADR 0045 / spec §1·§2).
 *
 * - 우리가 server, AI client(Claude·Cursor 등)가 호출
 * - read·analysis only — 매매 트리거 tool 영원히 노출 X
 * - JSON-RPC 2.0 over HTTP. SSE·stdio는 R2 이상
 *
 * 요청 헤더:
 *  - `X-MCP-Key` (선택, prd 필수): API key
 *
 * 응답 본문은 JSON-RPC 2.0 envelope. HTTP status는 항상 200 — 에러도 envelope 안에. (rate limit 만 429)
 */
@RestController
@RequestMapping("/mcp/v1")
@Tag(
    name = "MCP",
    description = """
        magicJar MCP 서버 (read·analysis only).

        Claude Desktop·Cursor 등 외부 AI 클라이언트가 magicJar 도메인을 호출할 수 있게 한다.
        매매 트리거(place_order·cancel_order 등) tool은 영원히 노출하지 않는다 (ADR 0045).

        Endpoint: `POST /mcp/v1/jsonrpc` (JSON-RPC 2.0 envelope).
        Methods: initialize · tools/list · tools/call · notifications/initialized.

        등록 가이드: `_workspace/operations/mcp-server-guide.md`.
    """,
)
class McpServerController(
    private val dispatcher: McpDispatcher,
    private val auth: McpAuth,
    private val rateLimiter: McpRateLimiter,
    private val auditLogger: McpAuditLogger,
    private val objectMapper: ObjectMapper,
) {
    private val log = LoggerFactory.getLogger(McpServerController::class.java)

    @Operation(
        summary = "MCP JSON-RPC 2.0 endpoint",
        description = """
            JSON-RPC 2.0 envelope를 받아 MCP method를 실행한다.

            지원 method:
            - `initialize`: capability handshake
            - `tools/list`: tool 카탈로그 반환 (R1: get_candles · get_quote · search_instruments · run_screener · get_journal)
            - `tools/call`: tool 실행 (params: {name, arguments})
            - `notifications/initialized`: client → server ack (응답 없음)
        """,
        responses = [
            ApiResponse(
                responseCode = "200",
                description = "JSON-RPC 응답 (성공·에러 모두 envelope 안)",
                content = [Content(mediaType = "application/json", schema = Schema(implementation = McpJsonRpcResponse::class))],
            ),
            ApiResponse(responseCode = "401", description = "인증 실패"),
            ApiResponse(responseCode = "429", description = "Rate limit 초과"),
        ],
    )
    @PostMapping(
        "/jsonrpc",
        consumes = [MediaType.APPLICATION_JSON_VALUE],
        produces = [MediaType.APPLICATION_JSON_VALUE],
    )
    fun jsonRpc(
        @RequestBody body: JsonNode,
        @RequestHeader(name = "X-MCP-Key", required = false) mcpKey: String?,
        servletRequest: HttpServletRequest,
    ): ResponseEntity<McpJsonRpcResponse> {
        val started = System.currentTimeMillis()
        val requestId = UUID.randomUUID().toString()
        val remoteAddr = servletRequest.remoteAddr

        // 1. 인증
        val authResult = auth.authenticate(mcpKey, remoteAddr)
        if (!authResult.authenticated) {
            val resp = errorResponse(body, McpErrorCodes.AUTHENTICATION_REQUIRED, "Authentication required")
            audit(requestId, null, remoteAddr, body, null, resp, started)
            return ResponseEntity.status(401).body(resp)
        }

        // 2. Rate limit
        val clientKey = authResult.clientKeyHash ?: "ip:$remoteAddr"
        if (!rateLimiter.allow(clientKey)) {
            val resp = errorResponse(body, McpErrorCodes.RATE_LIMIT_EXCEEDED, "Rate limit exceeded")
            audit(requestId, authResult.clientKeyHash, remoteAddr, body, null, resp, started)
            return ResponseEntity.status(429).body(resp)
        }

        // 3. envelope 파싱
        val request =
            try {
                parseRequest(body)
            } catch (e: Exception) {
                val resp = errorResponse(body, McpErrorCodes.INVALID_REQUEST, "Invalid JSON-RPC request: ${e.message}")
                audit(requestId, authResult.clientKeyHash, remoteAddr, body, null, resp, started)
                return ResponseEntity.ok(resp)
            }

        // 4. dispatch
        val toolName =
            if (request.method == "tools/call") {
                request.params?.get("name")?.asText()
            } else {
                null
            }

        return try {
            val result = dispatcher.dispatch(request)
            // notifications/* 는 응답 없음
            if (request.method.startsWith("notifications/")) {
                val emptyResp = McpJsonRpcResponse(id = request.id, result = null)
                audit(requestId, authResult.clientKeyHash, remoteAddr, body, toolName, emptyResp, started)
                return ResponseEntity.ok(emptyResp)
            }
            val resp = McpJsonRpcResponse(id = request.id, result = result)
            audit(requestId, authResult.clientKeyHash, remoteAddr, body, toolName, resp, started)
            ResponseEntity.ok(resp)
        } catch (e: McpException) {
            val resp =
                McpJsonRpcResponse(
                    id = request.id,
                    error = McpJsonRpcError(code = e.errorCode, message = e.message ?: "MCP error", data = e.data),
                )
            audit(requestId, authResult.clientKeyHash, remoteAddr, body, toolName, resp, started)
            ResponseEntity.ok(resp)
        } catch (e: Exception) {
            log.error("[MCP] internal error on method=${request.method}", e)
            val resp =
                errorResponseWithId(
                    request.id,
                    McpErrorCodes.INTERNAL_ERROR,
                    "Internal error: ${e.javaClass.simpleName}",
                )
            audit(requestId, authResult.clientKeyHash, remoteAddr, body, toolName, resp, started)
            ResponseEntity.status(500).body(resp)
        }
    }

    // ─────────────────────────────────────────────────────────

    private fun parseRequest(body: JsonNode): McpJsonRpcRequest {
        if (!body.isObject) {
            throw IllegalArgumentException("body must be JSON object")
        }
        val method = body.get("method")?.asText() ?: throw IllegalArgumentException("method required")
        val id = body.get("id")
        val params = body.get("params")
        val jsonrpc = body.get("jsonrpc")?.asText()
        if (jsonrpc != null && jsonrpc != "2.0") {
            throw IllegalArgumentException("unsupported jsonrpc version: $jsonrpc")
        }
        return McpJsonRpcRequest(jsonrpc = jsonrpc ?: "2.0", id = id, method = method, params = params)
    }

    private fun errorResponse(
        body: JsonNode,
        code: Int,
        message: String,
    ): McpJsonRpcResponse {
        val id = body.get("id")
        return errorResponseWithId(id, code, message)
    }

    private fun errorResponseWithId(
        id: JsonNode?,
        code: Int,
        message: String,
    ): McpJsonRpcResponse =
        McpJsonRpcResponse(
            id = id ?: NullNode.instance,
            error = McpJsonRpcError(code = code, message = message),
        )

    private fun audit(
        requestId: String,
        clientKeyHash: String?,
        clientAddress: String?,
        rawBody: JsonNode,
        toolName: String?,
        response: McpJsonRpcResponse,
        startedMs: Long,
    ) {
        try {
            val method = rawBody.get("method")?.asText() ?: "<unknown>"
            val params =
                if (rawBody.get("params")?.isNull == false) rawBody.get("params") else null
            val responseBytes =
                runCatching { objectMapper.writeValueAsBytes(response).size }.getOrNull()
            auditLogger.record(
                AuditRecord(
                    requestId = requestId,
                    clientKeyHash = clientKeyHash,
                    clientAddress = clientAddress,
                    method = method,
                    toolName = toolName,
                    params = params,
                    responseSizeBytes = responseBytes,
                    latencyMs = (System.currentTimeMillis() - startedMs).toInt(),
                    errorCode = response.error?.code,
                    errorMessage = response.error?.message,
                    calledAt = Instant.now(),
                ),
            )
        } catch (e: Exception) {
            log.warn("[MCP] audit fail (non-blocking): {}", e.message)
        }
    }
}

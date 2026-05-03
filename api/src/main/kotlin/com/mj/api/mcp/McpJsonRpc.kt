package com.mj.api.mcp

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.JsonNode

/**
 * JSON-RPC 2.0 envelope DTO + 에러 코드 enum (T17 / ADR 0045).
 *
 * spec §2 참조. id 필드는 string·int·null 모두 허용 — Jackson에 위임.
 */
data class McpJsonRpcRequest(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val method: String,
    val params: JsonNode? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class McpJsonRpcResponse(
    val jsonrpc: String = "2.0",
    val id: JsonNode? = null,
    val result: Any? = null,
    val error: McpJsonRpcError? = null,
)

data class McpJsonRpcError(
    val code: Int,
    val message: String,
    val data: Any? = null,
)

/** MCP 표준 + magicJar 확장 에러 코드 (spec §2-3). */
object McpErrorCodes {
    const val PARSE_ERROR = -32700
    const val INVALID_REQUEST = -32600
    const val METHOD_NOT_FOUND = -32601
    const val INVALID_PARAMS = -32602
    const val INTERNAL_ERROR = -32603

    // magicJar 확장
    const val TOOL_EXECUTION_ERROR = -32000
    const val TOOL_NOT_FOUND = -32001
    const val AUTHENTICATION_REQUIRED = -32002
    const val RATE_LIMIT_EXCEEDED = -32003
    const val FORBIDDEN_TOOL = -32004
}

/** 예외로 throw해서 catch 시 JSON-RPC error 응답 생성 — controller 단순화. */
class McpException(
    val errorCode: Int,
    message: String,
    val data: Any? = null,
) : RuntimeException(message)

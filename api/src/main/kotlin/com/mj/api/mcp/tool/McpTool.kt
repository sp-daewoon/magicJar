package com.mj.api.mcp.tool

import com.fasterxml.jackson.databind.JsonNode

/**
 * MCP tool 인터페이스 (T17 / ADR 0045).
 *
 * 각 tool은:
 *  - 고유 name (snake_case)
 *  - 한국어 description
 *  - JSON Schema (input + output)
 *  - 동기 invoke — read·analysis only, AI 호출 X
 *
 * R1 등록 5종: get_candles · get_quote · search_instruments · run_screener · get_journal.
 * write·매매 트리거 tool 영원히 등록 X (ADR 0045 절대 원칙).
 */
interface McpTool {
    val name: String
    val description: String
    val readOnly: Boolean get() = true
    val inputSchema: Map<String, Any>
    val outputSchema: Map<String, Any>

    /**
     * tool 실행. arguments는 inputSchema에 의해 이미 검증됨.
     *
     * @return JSON 직렬화 가능한 응답 객체 (Map·List·primitive). content[0].text로 감싸 client에 반환.
     */
    fun invoke(arguments: JsonNode?): Any
}

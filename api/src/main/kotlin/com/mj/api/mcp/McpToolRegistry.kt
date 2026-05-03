package com.mj.api.mcp

import com.fasterxml.jackson.databind.JsonNode
import com.mj.api.mcp.tool.McpTool
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * MCP tool 카탈로그 + JSON Schema validation (T17 / ADR 0045 §4).
 *
 * Spring이 등록한 모든 [McpTool] bean 자동 수집.
 *
 * validation 범위 (spec §4 모든 tool input_schema의 키):
 *  - type (object·string·integer·number·boolean·array)
 *  - required
 *  - properties (재귀)
 *  - pattern (regex)
 *  - enum
 *  - minimum / maximum (number·integer)
 *  - minLength
 *  - format=date-time / date / uuid (간단 검증)
 *  - items (array element schema)
 *
 * 미지원 키워드는 silently skip — 본 registry는 R1 tool 5종 schema 한정 검증기.
 */
@Component
class McpToolRegistry(
    tools: List<McpTool>,
) {
    private val byName: Map<String, McpTool> = tools.associateBy { it.name }

    fun list(): List<McpTool> = byName.values.sortedBy { it.name }

    fun get(name: String): McpTool? = byName[name]

    /** spec §4 카탈로그 직렬화 — tools/list 응답용. */
    fun catalog(): List<Map<String, Any>> =
        list().map { t ->
            mapOf(
                "name" to t.name,
                "description" to t.description,
                "inputSchema" to t.inputSchema,
                "outputSchema" to t.outputSchema,
                "readOnly" to t.readOnly,
            )
        }

    /**
     * arguments가 schema에 부합하는지 검증. 실패 시 [McpException] (-32602 INVALID_PARAMS).
     */
    fun validate(
        schema: Map<String, Any>,
        arguments: JsonNode?,
    ) {
        validate(schema, arguments, path = "$")
    }

    private fun validate(
        schema: Map<String, Any>,
        node: JsonNode?,
        path: String,
    ) {
        val type = schema["type"] as? String

        // null 허용은 본 registry 비대상 — null 들어오면 type 기준 검사
        val isNull = node == null || node.isNull
        if (isNull) {
            // required 컨테이너에서만 검증 — 여기선 pass
            return
        }

        when (type) {
            "object" -> {
                if (!node!!.isObject) {
                    fail("$path: expected object, got ${node.nodeType}")
                }
                @Suppress("UNCHECKED_CAST")
                val props = schema["properties"] as? Map<String, Map<String, Any>> ?: emptyMap()

                @Suppress("UNCHECKED_CAST")
                val required = schema["required"] as? List<String> ?: emptyList()

                for (key in required) {
                    val child = node.get(key)
                    if (child == null || child.isNull) {
                        fail("$path.$key: required")
                    }
                }
                for ((key, sub) in props) {
                    val child = node.get(key) ?: continue
                    validate(sub, child, "$path.$key")
                }
            }
            "string" -> {
                if (!node!!.isTextual) fail("$path: expected string")
                val v = node.asText()

                (schema["minLength"] as? Number)?.let { min ->
                    if (v.length < min.toInt()) fail("$path: minLength=$min")
                }
                (schema["pattern"] as? String)?.let { pat ->
                    if (!Regex(pat).containsMatchIn(v)) fail("$path: must match $pat")
                }
                @Suppress("UNCHECKED_CAST")
                (schema["enum"] as? List<Any>)?.let { allowed ->
                    if (allowed.none { it.toString() == v }) {
                        fail("$path: must be one of $allowed")
                    }
                }
                when (schema["format"] as? String) {
                    "date-time" -> {
                        try {
                            Instant.parse(v)
                        } catch (_: DateTimeParseException) {
                            fail("$path: invalid date-time (ISO-8601 required)")
                        }
                    }
                    "date" -> {
                        if (!v.matches(Regex("^\\d{4}-\\d{2}-\\d{2}$"))) {
                            fail("$path: invalid date (yyyy-MM-dd)")
                        }
                    }
                    "uuid" -> {
                        if (!v.matches(Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$"))) {
                            fail("$path: invalid uuid")
                        }
                    }
                }
            }
            "integer" -> {
                if (!node!!.canConvertToInt() && !node.canConvertToLong()) {
                    fail("$path: expected integer")
                }
                val v = node.asLong()
                (schema["minimum"] as? Number)?.let { if (v < it.toLong()) fail("$path: >= $it") }
                (schema["maximum"] as? Number)?.let { if (v > it.toLong()) fail("$path: <= $it") }
            }
            "number" -> {
                if (!node!!.isNumber) fail("$path: expected number")
                val v = node.asDouble()
                (schema["minimum"] as? Number)?.let { if (v < it.toDouble()) fail("$path: >= $it") }
                (schema["maximum"] as? Number)?.let { if (v > it.toDouble()) fail("$path: <= $it") }
            }
            "boolean" -> {
                if (!node!!.isBoolean) fail("$path: expected boolean")
            }
            "array" -> {
                if (!node!!.isArray) fail("$path: expected array")
                @Suppress("UNCHECKED_CAST")
                val items = schema["items"] as? Map<String, Any>
                if (items != null) {
                    node.forEachIndexed { idx, el -> validate(items, el, "$path[$idx]") }
                }
            }
            null -> {
                // type 미지정은 항상 통과 (oneOf 등 미지원)
            }
        }
    }

    private fun fail(msg: String): Nothing = throw McpException(McpErrorCodes.INVALID_PARAMS, "Invalid params: $msg")
}

private fun JsonNode.forEachIndexed(action: (Int, JsonNode) -> Unit) {
    var i = 0
    val it = elements()
    while (it.hasNext()) {
        action(i++, it.next())
    }
}

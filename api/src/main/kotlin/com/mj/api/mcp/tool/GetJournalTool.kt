package com.mj.api.mcp.tool

import com.fasterxml.jackson.databind.JsonNode
import com.mj.api.application.order.OrderApiService
import org.springframework.stereotype.Component
import java.time.Instant

/**
 * `get_journal` (read) — spec §4-5.
 *
 * 사용자의 주문·체결 이력 조회. 1차는 단일 시드 사용자 (멀티테넌트 인증은 post-MVP).
 * orders 테이블 (1번 트랙) 기준 최근 100건 + 종목·기간 필터.
 *
 * AI client에게 매매 트리거를 노출하지 않으므로 본 tool은 **조회 전용**.
 */
@Component
class GetJournalTool(
    private val orderApiService: OrderApiService,
) : McpTool {
    override val name: String = "get_journal"
    override val description: String =
        "사용자의 주문·체결 이력(매매일지)을 조회한다. 종목·기간 필터 지원. " +
            "본 tool은 read-only — AI client가 매매를 트리거하는 것은 영원히 차단(ADR 0045)."

    override val inputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "userId" to
                        mapOf(
                            "type" to "string",
                            "format" to "uuid",
                            "description" to "사용자 UUID (1차 default 시드 사용자)",
                        ),
                    "from" to
                        mapOf("type" to "string", "format" to "date-time", "description" to "조회 시작 시각"),
                    "to" to
                        mapOf("type" to "string", "format" to "date-time", "description" to "조회 종료 시각"),
                    "code" to
                        mapOf("type" to "string", "pattern" to "^[0-9]{6}$", "description" to "종목 필터 (옵션)"),
                    "limit" to
                        mapOf("type" to "integer", "minimum" to 1, "maximum" to 200, "description" to "결과 최대 (default 100)"),
                ),
        )

    override val outputSchema: Map<String, Any> =
        mapOf(
            "type" to "object",
            "properties" to
                mapOf(
                    "userId" to mapOf("type" to "string"),
                    "orders" to
                        mapOf(
                            "type" to "array",
                            "items" to
                                mapOf(
                                    "type" to "object",
                                    "properties" to
                                        mapOf(
                                            "orderId" to mapOf("type" to "string"),
                                            "code" to mapOf("type" to "string"),
                                            "side" to mapOf("type" to "string"),
                                            "qty" to mapOf("type" to "integer"),
                                            "price" to mapOf("type" to "number"),
                                            "orderType" to mapOf("type" to "string"),
                                            "status" to mapOf("type" to "string"),
                                            "filledQty" to mapOf("type" to "integer"),
                                            "avgFillPrice" to mapOf("type" to "number"),
                                            "createdAt" to mapOf("type" to "string"),
                                        ),
                                ),
                        ),
                    "count" to mapOf("type" to "integer"),
                ),
        )

    override fun invoke(arguments: JsonNode?): Any {
        val userId =
            arguments
                ?.get("userId")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_USER_ID
        val from =
            arguments
                ?.get("from")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) }
        val to =
            arguments
                ?.get("to")
                ?.asText()
                ?.takeIf { it.isNotBlank() }
                ?.let { Instant.parse(it) }
        val codeFilter = arguments?.get("code")?.asText()?.takeIf { it.isNotBlank() }
        val limit = arguments?.get("limit")?.asInt() ?: 100

        val all = orderApiService.listOrders(userId)
        val filtered =
            all
                .asSequence()
                .filter { codeFilter == null || it.code == codeFilter }
                .filter { o -> from == null || !o.createdAt.toInstant().isBefore(from) }
                .filter { o -> to == null || !o.createdAt.toInstant().isAfter(to) }
                .take(limit)
                .toList()

        return mapOf(
            "userId" to userId,
            "orders" to
                filtered.map { o ->
                    mapOf(
                        "orderId" to o.orderId.toString(),
                        "kisOrderNo" to (o.kisOrderNo ?: ""),
                        "code" to o.code,
                        "side" to o.side.name,
                        "qty" to o.qty,
                        "price" to (o.price?.toPlainString() ?: ""),
                        "orderType" to o.orderType.name,
                        "status" to o.status.name,
                        "filledQty" to o.filledQty,
                        "avgFillPrice" to (o.avgFillPrice?.toPlainString() ?: ""),
                        "createdAt" to o.createdAt.toString(),
                        "rejectReasonCode" to (o.rejectReasonCode ?: ""),
                    )
                },
            "count" to filtered.size,
        )
    }

    companion object {
        // 1번 트랙 시드 사용자 — OrderController.DEFAULT_USER_ID 동일.
        // 인증 도입 시 (ISSUE-006 후속) @AuthenticationPrincipal에서 추출하도록 교체.
        const val DEFAULT_USER_ID: String = "74683223"
    }
}

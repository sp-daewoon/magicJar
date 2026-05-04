package com.mj.api.controller

import com.mj.api.application.order.OrderApiService
import com.mj.api.controller.dto.OrderRequest
import com.mj.api.controller.dto.OrderResponse
import com.mj.api.controller.dto.OrderTriggerResponse
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 주문 REST endpoint. spec §6.3, §12.
 *
 * - POST /api/orders → Kafka order.intent.v1 publish (수동 주문 트리거)
 * - GET  /api/orders → 사용자별 주문 목록
 * - GET  /api/orders/open → 미체결만
 *
 * 인증 도입 시(ISSUE-006 후속) userId는 `@AuthenticationPrincipal`에서 추출 — 본 컨트롤러 변경 0.
 * 1차에는 `?userId=...` query param 또는 default 시드 사용자.
 */
@RestController
@RequestMapping("/api/orders")
@Tag(name = "Order", description = "주문 트리거·조회 — 1번 트랙 (KIS 주문 어댑터)")
class OrderController(
    private val service: OrderApiService,
) {
    @Operation(
        summary = "주문 목록 조회",
        description = "사용자별 최근 100건 (모든 상태). 인증 도입 전엔 ?userId 명시 또는 default 시드.",
    )
    @GetMapping(produces = [MediaType.APPLICATION_JSON_VALUE])
    fun list(
        @Parameter(description = "사용자 ID (default DEFAULT_USER_ID)")
        @RequestParam(required = false) userId: String?,
    ): List<OrderResponse> = service.listOrders(userId ?: DEFAULT_USER_ID)

    @Operation(
        summary = "미체결 주문 조회",
        description = "PENDING·PARTIALLY_FILLED 상태만",
    )
    @GetMapping("/open", produces = [MediaType.APPLICATION_JSON_VALUE])
    fun listOpen(
        @RequestParam(required = false) userId: String?,
    ): List<OrderResponse> = service.listOpenOrders(userId ?: DEFAULT_USER_ID)

    @Operation(
        summary = "수동 주문 트리거",
        description = "Kafka order.intent.v1로 의도 발행. Guard chain · KisOrderPort 흐름은 consumer 모듈에서 처리.",
    )
    @PostMapping(consumes = [MediaType.APPLICATION_JSON_VALUE], produces = [MediaType.APPLICATION_JSON_VALUE])
    fun trigger(
        @RequestBody body: OrderRequest,
        @RequestParam(required = false) userId: String?,
    ): OrderTriggerResponse = service.triggerOrder(userId ?: DEFAULT_USER_ID, body)

    companion object {
        // 인증 도입 시 SecurityContext에서 가져오도록 변경. 1차는 시드 사용자 고정.
        const val DEFAULT_USER_ID: String = "74683223"
    }
}

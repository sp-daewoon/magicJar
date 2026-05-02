package com.mj.api.controller.dto

import com.mj.api.infrastructure.persistence.order.OrderApiEntity
import com.mj.domain.common.OrderStatus
import com.mj.domain.common.OrderType
import com.mj.domain.common.Side
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.OffsetDateTime
import java.util.UUID

@Schema(description = "주문 1건의 응답 DTO. /api/orders·STOMP /topic/orders/{userId} 공통.")
data class OrderResponse(
    @field:Schema(description = "주문 ID(UUID)") val orderId: UUID,
    @field:Schema(description = "KIS 주문번호 (REJECTED는 null)") val kisOrderNo: String?,
    @field:Schema(description = "거래소 조직번호 (정정/취소 호출에 사용, REJECTED는 null)") val krxFwdgOrdOrgno: String?,
    @field:Schema(description = "계좌번호") val accountNo: String,
    @field:Schema(description = "KIS 모드", example = "MOCK_KIS") val kisMode: String,
    @field:Schema(description = "종목코드", example = "005930") val code: String,
    @field:Schema(description = "매도/매수") val side: Side,
    @field:Schema(description = "주문수량") val qty: Int,
    @field:Schema(description = "주문가 (LIMIT만)") val price: BigDecimal?,
    @field:Schema(description = "주문구분") val orderType: OrderType,
    @field:Schema(description = "현재 상태") val status: OrderStatus,
    @field:Schema(description = "체결 수량") val filledQty: Int,
    @field:Schema(description = "평균 체결가") val avgFillPrice: BigDecimal?,
    @field:Schema(description = "거부 사유 레거시 (REJECTED일 때)") val rejectReason: String?,
    @field:Schema(description = "거부 코드 구조화 (ADR 0034, 예: KILL_SWITCH, SESSION_CLOSED, KIS_API:40100000)") val rejectReasonCode: String?,
    @field:Schema(description = "거부 상세 메시지") val rejectDetail: String?,
    @field:Schema(description = "거부 출처 — GUARD | KIS_API | INTERNAL") val rejectSource: String?,
    @field:Schema(description = "생성 시각 (ISO 8601)") val createdAt: OffsetDateTime,
    @field:Schema(description = "최근 갱신 시각") val updatedAt: OffsetDateTime,
)

fun OrderApiEntity.toResponse(): OrderResponse =
    OrderResponse(
        orderId = orderId,
        kisOrderNo = kisOrderNo,
        krxFwdgOrdOrgno = krxFwdgOrdOrgno,
        accountNo = accountNo,
        kisMode = kisMode,
        code = code,
        side = side,
        qty = qty,
        price = price,
        orderType = orderType,
        status = status,
        filledQty = filledQty,
        avgFillPrice = avgFillPrice,
        rejectReason = rejectReason,
        rejectReasonCode = rejectReasonCode,
        rejectDetail = rejectDetail,
        rejectSource = rejectSource,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

@Schema(description = "수동 주문 생성 요청 DTO. POST /api/orders.")
data class OrderRequest(
    @field:Schema(description = "종목코드", example = "005930") val code: String,
    @field:Schema(description = "매도/매수", example = "BUY") val side: Side,
    @field:Schema(description = "주문수량", example = "1") val qty: Int,
    @field:Schema(description = "주문가 (LIMIT만 사용)", example = "70000") val price: BigDecimal?,
    @field:Schema(description = "주문구분", example = "LIMIT") val orderType: OrderType,
)

@Schema(description = "POST /api/orders 응답 — Kafka order.intent.v1 publish 결과.")
data class OrderTriggerResponse(
    @field:Schema(description = "발행된 intent ID — 이 ID로 추후 /api/orders 조회") val intentId: UUID,
    @field:Schema(description = "Kafka publish 시각") val publishedAt: OffsetDateTime,
)

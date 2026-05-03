package com.mj.api.controller.dto

import com.mj.domain.market.Orderbook
import com.mj.domain.market.OrderbookLevel
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal
import java.time.Instant

/**
 * 호가창 응답 DTO.
 * KIS FHKST01010200 (inquire-asking-price-exp-ccn) 기반.
 */
@Schema(description = "종목 호가창 응답")
data class OrderbookResponse(
    @Schema(description = "종목 코드", example = "005930")
    val code: String,
    @Schema(description = "호가 기준 시각 (ISO-8601 UTC)", example = "2026-05-04T06:59:59Z")
    val acceptedAt: Instant,
    @Schema(description = "매도 호가 10단 (index 0=1호가 — 낮은 가격부터)")
    val askLevels: List<OrderbookLevelDto>,
    @Schema(description = "매수 호가 10단 (index 0=1호가 — 높은 가격부터)")
    val bidLevels: List<OrderbookLevelDto>,
    @Schema(description = "총 매도 잔량 (주)", example = "1234567")
    val totalAskQty: Long,
    @Schema(description = "총 매수 잔량 (주)", example = "9876543")
    val totalBidQty: Long,
    @Schema(description = "현재가 (원)", example = "220500")
    val currentPrice: BigDecimal?,
    @Schema(description = "예상체결가 (원)", example = "221000")
    val anticipatedPrice: BigDecimal?,
    @Schema(description = "예상체결수량 (주)", example = "5000")
    val anticipatedVolume: Long?,
)

@Schema(description = "호가 단건 (가격 + 잔량)")
data class OrderbookLevelDto(
    @Schema(description = "호가 가격 (원)", example = "221000")
    val price: BigDecimal,
    @Schema(description = "잔량 (주)", example = "10013")
    val qty: Long,
    @Schema(description = "잔량 증감 (전 호가 대비)", example = "-500")
    val qtyChange: Long,
)

fun Orderbook.toResponse(): OrderbookResponse =
    OrderbookResponse(
        code = code,
        acceptedAt = acceptedAt,
        askLevels = askLevels.map { it.toLevelDto() },
        bidLevels = bidLevels.map { it.toLevelDto() },
        totalAskQty = totalAskQty,
        totalBidQty = totalBidQty,
        currentPrice = currentPrice,
        anticipatedPrice = anticipatedPrice,
        anticipatedVolume = anticipatedVolume,
    )

fun OrderbookLevel.toLevelDto(): OrderbookLevelDto =
    OrderbookLevelDto(
        price = price,
        qty = qty,
        qtyChange = qtyChange,
    )

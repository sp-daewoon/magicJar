package com.mj.api.controller.dto

import com.mj.domain.market.RecentTrade
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * 실시간 체결 단건 응답 DTO.
 * KIS FHKST01010300 (inquire-ccnl) 기반.
 */
@Schema(description = "체결 단건 응답")
data class RecentTradeResponse(
    @Schema(description = "종목 코드", example = "005930")
    val code: String,
    @Schema(description = "체결 시각 (KIS HHmmss, 예: '155959')", example = "155959")
    val tradeTime: String,
    @Schema(description = "체결 가격 (원)", example = "220500")
    val price: BigDecimal,
    @Schema(description = "체결 수량 (주)", example = "2")
    val volume: Long,
    @Schema(
        description = "전일 대비 (원, 부호 포함)",
        example = "-5500",
    )
    val priceChange: BigDecimal,
    @Schema(
        description = "전일 대비 부호 코드 (1=상한/2=상승/3=보합/4=하한/5=하락)",
        example = "5",
    )
    val priceChangeSign: String,
    @Schema(description = "전일 대비율 (%)", example = "-2.43")
    val changeRate: BigDecimal,
)

fun RecentTrade.toResponse(): RecentTradeResponse =
    RecentTradeResponse(
        code = code,
        tradeTime = tradeTime,
        price = price,
        volume = volume,
        priceChange = priceChange,
        priceChangeSign = priceChangeSign,
        changeRate = changeRate,
    )

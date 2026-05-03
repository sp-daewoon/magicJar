package com.mj.api.controller.dto

import com.mj.domain.market.index.MarketIndex
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

/**
 * 시장 지수 응답 DTO (T21-T3).
 */
@Schema(description = "시장 지수 정보 (KOSPI / KOSDAQ)")
data class MarketIndexResponse(
    @Schema(description = "지수 코드 (0001=KOSPI, 1001=KOSDAQ)", example = "0001")
    val code: String,
    @Schema(description = "지수명", example = "KOSPI")
    val name: String,
    @Schema(description = "현재 지수값", example = "2650.34")
    val currentValue: BigDecimal,
    @Schema(description = "전일 종가", example = "2630.12")
    val prevClose: BigDecimal,
    @Schema(description = "등락률 (%)", example = "0.77")
    val changePercent: BigDecimal,
    @Schema(description = "누적 거래량", example = "450123456")
    val volume: Long,
    @Schema(description = "시장 상태 (장전/장중/장마감 등)", example = "장중")
    val session: String?,
    @Schema(description = "거래대금 (원)", example = "8500000000000")
    val tradingValue: BigDecimal?,
)

/** MarketIndex → MarketIndexResponse 변환 */
fun MarketIndex.toResponse(): MarketIndexResponse =
    MarketIndexResponse(
        code = code,
        name = name,
        currentValue = currentValue,
        prevClose = prevClose,
        changePercent = changePercent,
        volume = volume,
        session = session,
        tradingValue = tradingValue,
    )

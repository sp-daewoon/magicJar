package com.mj.api.controller.dto

import com.mj.domain.common.Market
import com.mj.domain.market.instrument.Instrument
import com.mj.domain.market.instrument.InstrumentType
import io.swagger.v3.oas.annotations.media.Schema

/**
 * 종목 검색 응답 DTO (T3-T5).
 */
@Schema(description = "종목 검색 결과 항목")
data class InstrumentSearchResponse(
    @Schema(description = "KRX 종목 코드 (6자리)", example = "005930")
    val code: String,
    @Schema(description = "종목 한글명", example = "삼성전자")
    val name: String,
    @Schema(description = "영문명", example = "Samsung Electronics Co., Ltd.")
    val nameEn: String?,
    @Schema(description = "상장 시장", example = "KOSPI", allowableValues = ["KOSPI", "KOSDAQ", "KONEX", "ETF", "ETN"])
    val market: Market,
    @Schema(description = "상품 유형", example = "STOCK", allowableValues = ["STOCK", "ETF", "ETN", "PREFERRED", "WARRANT", "REIT"])
    val type: InstrumentType,
    @Schema(description = "업종", example = "전기·전자")
    val sector: String?,
    @Schema(description = "거래 가능 여부 (거래정지·상폐 시 false)", example = "true")
    val active: Boolean,
)

fun Instrument.toSearchResponse(): InstrumentSearchResponse =
    InstrumentSearchResponse(
        code = code,
        name = name,
        nameEn = nameEn,
        market = market,
        type = type,
        sector = sector,
        active = active,
    )

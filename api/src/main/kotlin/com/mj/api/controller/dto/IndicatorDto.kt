package com.mj.api.controller.dto

import com.mj.domain.strategy.indicator.IndicatorCategory
import com.mj.domain.strategy.indicator.IndicatorDescriptor
import com.mj.domain.strategy.indicator.IndicatorResult
import com.mj.domain.strategy.indicator.IndicatorSpec
import com.mj.domain.strategy.indicator.ParamType
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

// ─────────────────────────────────────────────
// 카탈로그 응답 DTO
// ─────────────────────────────────────────────

@Schema(description = "지표 파라미터 정의")
data class IndicatorParamDefResponse(
    @Schema(description = "파라미터 키", example = "period")
    val key: String,
    @Schema(description = "타입 (INT/FLOAT/STRING)", example = "INT")
    val type: ParamType,
    @Schema(description = "기본값", example = "14")
    val defaultValue: Any?,
    @Schema(description = "설명", example = "이동평균 기간")
    val description: String,
)

@Schema(description = "지표 메타 정보")
data class IndicatorDescriptorResponse(
    @Schema(description = "소문자 지표 ID", example = "rsi")
    val id: String,
    @Schema(description = "표시 이름", example = "RSI")
    val name: String,
    @Schema(description = "카테고리", example = "MOMENTUM")
    val category: IndicatorCategory,
    @Schema(description = "파라미터 정의 목록")
    val params: List<IndicatorParamDefResponse>,
    @Schema(description = "출력 키 목록", example = "[\"rsi\"]")
    val outputKeys: List<String>,
)

fun IndicatorDescriptor.toResponse(): IndicatorDescriptorResponse =
    IndicatorDescriptorResponse(
        id = id,
        name = name,
        category = category,
        params =
            params.map {
                IndicatorParamDefResponse(
                    key = it.key,
                    type = it.type,
                    defaultValue = it.defaultValue,
                    description = it.description,
                )
            },
        outputKeys = outputKeys,
    )

// ─────────────────────────────────────────────
// 계산 요청 DTO
// ─────────────────────────────────────────────

@Schema(description = "캔들 OHLCV 입력 (지표 계산용)")
data class CandleInputDto(
    @Schema(description = "시각 (ISO-8601)", example = "2026-01-01T00:00:00Z")
    val time: String,
    @Schema(description = "시가", example = "60000")
    val open: BigDecimal,
    @Schema(description = "고가", example = "61000")
    val high: BigDecimal,
    @Schema(description = "저가", example = "59000")
    val low: BigDecimal,
    @Schema(description = "종가", example = "60500")
    val close: BigDecimal,
    @Schema(description = "거래량", example = "1000000")
    val volume: Long,
)

@Schema(description = "지표 계산 요청")
data class IndicatorComputeRequest(
    @Schema(description = "지표 명세")
    val spec: IndicatorSpecDto,
    @Schema(description = "캔들 시계열 (시간 오름차순)")
    val candles: List<CandleInputDto>,
)

@Schema(description = "지표 명세 (source-agnostic)")
data class IndicatorSpecDto(
    @Schema(description = "소문자 지표 ID", example = "rsi")
    val name: String,
    @Schema(description = "파라미터 Map", example = "{\"period\": 14}")
    val params: Map<String, Any> = emptyMap(),
)

fun IndicatorSpecDto.toDomain(): IndicatorSpec = IndicatorSpec(name = name.lowercase(), params = params)

// ─────────────────────────────────────────────
// 계산 결과 응답 DTO
// ─────────────────────────────────────────────

@Schema(description = "지표 계산 결과 한 점")
data class IndicatorPointResponse(
    @Schema(description = "시각 epoch seconds", example = "1704067200")
    val time: Long,
    @Schema(description = "지표 값 Map", example = "{\"rsi\": 65.4}")
    val values: Map<String, BigDecimal>,
)

@Schema(description = "지표 계산 결과 시계열")
data class IndicatorResultResponse(
    @Schema(description = "지표 명세")
    val spec: IndicatorSpecDto,
    @Schema(description = "시계열 포인트 (null 구간 제외)")
    val series: List<IndicatorPointResponse>,
    @Schema(description = "계산에 사용된 source", example = "native")
    val source: String,
    @Schema(description = "전체 입력 중 유효 포인트 수", example = "89")
    val pointCount: Int,
)

fun IndicatorResult.toResponse(): IndicatorResultResponse =
    IndicatorResultResponse(
        spec = IndicatorSpecDto(name = spec.name, params = spec.params),
        series =
            series.map { p ->
                IndicatorPointResponse(
                    time = p.time.epochSecond,
                    values = p.values,
                )
            },
        source = source,
        pointCount = series.size,
    )

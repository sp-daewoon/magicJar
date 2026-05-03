package com.mj.api.controller

import com.mj.api.controller.dto.IndicatorComputeRequest
import com.mj.api.controller.dto.IndicatorDescriptorResponse
import com.mj.api.controller.dto.IndicatorResultResponse
import com.mj.api.controller.dto.toDomain
import com.mj.api.controller.dto.toResponse
import com.mj.domain.common.Candle
import com.mj.domain.common.Interval
import com.mj.domain.strategy.indicator.IndicatorCategory
import com.mj.domain.strategy.indicator.IndicatorEngine
import com.mj.domain.strategy.indicator.port.IndicatorComputeException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

@Tag(
    name = "Indicator",
    description = """
        기술지표 카탈로그 조회 및 계산 API (ADR 0039).

        GET /api/indicators    — 80개 KIS Strategy Builder 호환 지표 카탈로그 반환.
        POST /api/indicators/compute — 지표 명세(spec) + 캔들 → 시계열 계산.

        source 우선순위: native(50) > kis-strategy-builder(100) > ta-lib(200, 미구현) > krx(300, 미구현).
        현재 native 구현 지표: ma·ema·rsi·macd·bb·vwap·stochastic_k·stochastic_d·atr·cci·obv·disparity·adx·volume_ma
    """,
)
@RestController
@RequestMapping("/api/indicators")
class IndicatorController(
    private val indicatorEngine: IndicatorEngine,
) {
    @Operation(
        summary = "지표 카탈로그 조회",
        description = """
            등록된 모든 지표 descriptor를 반환한다.

            - category 파라미터로 특정 카테고리만 필터링 가능.
            - 카테고리: TREND / MOMENTUM / VOLUME / VOLATILITY / OSCILLATOR / OTHER
            - 각 descriptor: id·name·category·params(기본값 포함)·outputKeys
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "카탈로그 목록 (0건이어도 200)"),
            ApiResponse(responseCode = "400", description = "유효하지 않은 category 파라미터"),
        ],
    )
    @GetMapping
    fun catalog(
        @Parameter(description = "카테고리 필터 (미지정 시 전체)", example = "MOMENTUM")
        @RequestParam(required = false) category: String?,
    ): ResponseEntity<List<IndicatorDescriptorResponse>> {
        val all = indicatorEngine.allDescriptors()
        val filtered =
            if (category.isNullOrBlank()) {
                all
            } else {
                val cat =
                    runCatching { IndicatorCategory.valueOf(category.uppercase()) }
                        .getOrElse { return ResponseEntity.badRequest().build() }
                all.filter { it.category == cat }
            }
        return ResponseEntity.ok(filtered.map { it.toResponse() })
    }

    @Operation(
        summary = "지표 계산",
        description = """
            지표 명세(spec)와 캔들 시계열을 받아 시계열 계산 결과를 반환한다.

            - spec.name: 소문자 지표 ID (예: "rsi", "macd", "bb")
            - spec.params: 파라미터 Map (미지정 시 기본값)
            - candles: OHLCV 배열 (시간 오름차순, time은 ISO-8601)
            - 응답 series는 null 구간(기간 부족) 제외 — 유효한 포인트만.
            - MACD/BB 등 다중 출력 지표는 values Map에 여러 키.

            예시 (RSI 14):
            {
              "spec": {"name": "rsi", "params": {"period": 14}},
              "candles": [...]
            }
        """,
    )
    @ApiResponses(
        value = [
            ApiResponse(responseCode = "200", description = "계산 성공"),
            ApiResponse(responseCode = "400", description = "지원하지 않는 지표 ID 또는 candles 파라미터 오류"),
            ApiResponse(responseCode = "422", description = "지표 계산 실패 (데이터 부족 등)"),
        ],
    )
    @PostMapping("/compute")
    fun compute(
        @RequestBody request: IndicatorComputeRequest,
    ): ResponseEntity<IndicatorResultResponse> {
        if (request.candles.isEmpty()) {
            return ResponseEntity.badRequest().build()
        }
        val spec = request.spec.toDomain()
        val candles =
            request.candles.mapNotNull { dto ->
                val time =
                    runCatching { Instant.parse(dto.time) }
                        .getOrElse { return ResponseEntity.badRequest().build() }
                Candle(
                    code = "UNKNOWN",
                    interval = Interval.D1,
                    openTime = time,
                    open = dto.open,
                    high = dto.high,
                    low = dto.low,
                    close = dto.close,
                    volume = dto.volume,
                    tradeCount = 0L,
                )
            }

        val result =
            runCatching { indicatorEngine.calculate(spec, candles) }
                .getOrElse { e ->
                    return when (e) {
                        is IndicatorComputeException -> ResponseEntity.unprocessableEntity().build()
                        is IllegalArgumentException -> ResponseEntity.badRequest().build()
                        else -> ResponseEntity.internalServerError().build()
                    }
                }
        return ResponseEntity.ok(result.toResponse())
    }
}

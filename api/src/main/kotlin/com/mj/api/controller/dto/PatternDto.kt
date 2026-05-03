package com.mj.api.controller.dto

import com.mj.domain.strategy.pattern.CandlePattern
import com.mj.domain.strategy.pattern.PatternCategory
import com.mj.domain.strategy.pattern.PatternDirection
import com.mj.domain.strategy.pattern.PatternMatch
import io.swagger.v3.oas.annotations.media.Schema
import java.math.BigDecimal

// ─────────────────────────────────────────────
// 캔들 입력 DTO
//   트랙5 IndicatorDto의 CandleInputDto와 명세 동일.
//   머지 후 통합은 별도 사이클에서 검토 (현재 두 컨트롤러 모두 자기 DTO 사용).
// ─────────────────────────────────────────────

@Schema(description = "캔들 OHLCV 입력 (패턴 인식용)")
data class PatternCandleInputDto(
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

// ─────────────────────────────────────────────
// 카탈로그 응답 DTO
// ─────────────────────────────────────────────

@Schema(description = "캔들스틱 패턴 메타 정보")
data class PatternDescriptorResponse(
    @Schema(description = "snake_case 식별자", example = "morning_star")
    val code: String,
    @Schema(description = "영문 이름", example = "Morning Star")
    val nameEn: String,
    @Schema(description = "한국어 이름", example = "샛별")
    val nameKo: String,
    @Schema(description = "카테고리", example = "BULLISH_REVERSAL")
    val category: PatternCategory,
    @Schema(description = "기본 방향 (REVERSAL_DIRECTIONAL은 NEUTRAL이며 인식 결과로 동적 결정)", example = "BULL")
    val defaultDirection: PatternDirection,
    @Schema(description = "매칭에 필요한 캔들 수 (1~5)", example = "3")
    val candleCount: Int,
    @Schema(description = "한국어 1줄 설명")
    val description: String,
)

fun CandlePattern.toResponse(): PatternDescriptorResponse =
    PatternDescriptorResponse(
        code = code,
        nameEn = nameEn,
        nameKo = nameKo,
        category = category,
        defaultDirection = defaultDirection,
        candleCount = candleCount,
        description = description,
    )

// ─────────────────────────────────────────────
// 인식 요청 DTO
// ─────────────────────────────────────────────

@Schema(description = "캔들스틱 패턴 인식 요청")
data class PatternRecognizeRequest(
    @Schema(description = "캔들 시계열 (시간 오름차순). 같은 종목·같은 인터벌 가정")
    val candles: List<PatternCandleInputDto>,
    @Schema(
        description = "끝에서 거꾸로 검사할 캔들 수. 미지정 시 50",
        example = "50",
    )
    val lookback: Int? = null,
    @Schema(
        description = "특정 패턴 코드만 매칭 검사 (미지정 시 전체 카탈로그)",
        example = "[\"morning_star\", \"hammer\"]",
    )
    val patterns: List<String>? = null,
)

// ─────────────────────────────────────────────
// 인식 결과 응답 DTO
// ─────────────────────────────────────────────

@Schema(description = "패턴 매칭 1건")
data class PatternMatchResponse(
    @Schema(description = "매칭된 패턴 코드", example = "morning_star")
    val patternCode: String,
    @Schema(description = "매칭된 패턴 한글명", example = "샛별")
    val patternNameKo: String,
    @Schema(description = "실제 방향 (BULL/BEAR/NEUTRAL)", example = "BULL")
    val direction: PatternDirection,
    @Schema(description = "카테고리", example = "BULLISH_REVERSAL")
    val category: PatternCategory,
    @Schema(description = "매칭 시작 인덱스 (요청 candles 배열 기준)", example = "10")
    val startIndex: Int,
    @Schema(description = "매칭 종료 인덱스", example = "12")
    val endIndex: Int,
    @Schema(description = "매칭 정합도 (0.0~1.0)", example = "0.95")
    val strength: Double,
    @Schema(description = "endIndex 캔들의 epoch second", example = "1704067200")
    val endTime: Long,
)

fun PatternMatch.toResponse(): PatternMatchResponse =
    PatternMatchResponse(
        patternCode = pattern.code,
        patternNameKo = pattern.nameKo,
        direction = direction,
        category = pattern.category,
        startIndex = startIndex,
        endIndex = endIndex,
        strength = strength,
        endTime = endOpenTime.epochSecond,
    )

@Schema(description = "패턴 인식 응답")
data class PatternRecognizeResponse(
    @Schema(description = "검사한 캔들 수", example = "100")
    val candleCount: Int,
    @Schema(description = "검사 lookback", example = "50")
    val lookback: Int,
    @Schema(description = "발견된 매칭 (endIndex 오름차순)")
    val matches: List<PatternMatchResponse>,
)
